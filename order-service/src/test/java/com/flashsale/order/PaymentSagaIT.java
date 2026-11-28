package com.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderFailed;
import com.flashsale.common.event.PaymentCompleted;
import com.flashsale.common.event.PaymentFailed;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The order side of the payment saga, against real Postgres and Kafka: payment events in → status change (+ an
 * order.failed event on a decline). Events are delivered at-least-once, so every case is also tried twice.
 */
@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listeners and outbox relay running and
// would react to the next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentSagaIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    private static final FakeCatalog CATALOG = new FakeCatalog();
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void catalog(DynamicPropertyRegistry registry) {
        registry.add("catalog.base-url", CATALOG::baseUrl);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    KafkaTemplate<String, String> kafka;

    KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        CATALOG.reset();
        CATALOG.product(1, "10.00", 100);
        CATALOG.product(2, "2.50", 100);
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(OrderFailed.TOPIC));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void paymentCompletedConfirmsTheOrderOnceEvenIfDeliveredTwice() throws Exception {
        UUID orderId = createOrder();
        EventEnvelope<PaymentCompleted> event = envelope(PaymentCompleted.TYPE,
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("22.50")));

        publish(PaymentCompleted.TOPIC, orderId, event);
        awaitTrue(() -> "CONFIRMED".equals(status(orderId)));
        Object firstUpdate = jdbc.queryForObject("SELECT updated_at FROM orders WHERE id = ?", Object.class, orderId);

        publish(PaymentCompleted.TOPIC, orderId, event); // redelivery: same eventId
        // A skipped duplicate leaves no trace, so publish a marker after it with the same key (same partition,
        // processed in order) and wait for the marker: by then the duplicate has been handled.
        EventEnvelope<PaymentCompleted> marker = envelope(PaymentCompleted.TYPE,
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("22.50")));
        publish(PaymentCompleted.TOPIC, orderId, marker);
        awaitTrue(() -> processedCount(marker.eventId()) == 1);

        assertThat(status(orderId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM orders WHERE id = ?", Object.class, orderId))
                .as("the redelivery changed nothing").isEqualTo(firstUpdate);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void paymentFailedFailsTheOrderAndPublishesOneOrderFailedWithItsItems() throws Exception {
        UUID orderId = createOrder();
        EventEnvelope<PaymentFailed> event = envelope(PaymentFailed.TYPE,
                new PaymentFailed(orderId, UUID.randomUUID(), "DECLINED"));

        publish(PaymentFailed.TOPIC, orderId, event);
        publish(PaymentFailed.TOPIC, orderId, event); // redelivery: must not release the stock twice

        awaitTrue(() -> "FAILED".equals(status(orderId)));
        List<ConsumerRecord<String, String>> out = awaitRecords(orderId.toString(), 1);
        out.addAll(drain(orderId.toString(), Duration.ofSeconds(3))); // a second OrderFailed would show up here
        System.out.printf("%n=== SAGA: PaymentFailed delivered 2x -> order %s, OrderFailed events %d (expected 1) ===%n%n",
                status(orderId), out.size());

        assertThat(jdbc.queryForObject("SELECT failure_reason FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("PAYMENT_DECLINED");
        assertThat(out).hasSize(1);
        JsonNode data = json.readTree(out.get(0).value()).get("data");
        assertThat(data.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(data.get("reason").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(data.get("items")).hasSize(2);
        assertThat(data.get("items").get(0).get("productId").asLong()).isEqualTo(1);
        assertThat(data.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void aLatePaymentCompletedCannotResurrectAFailedOrder() throws Exception {
        UUID orderId = createOrder();
        publish(PaymentFailed.TOPIC, orderId,
                envelope(PaymentFailed.TYPE, new PaymentFailed(orderId, UUID.randomUUID(), "DECLINED")));
        awaitTrue(() -> "FAILED".equals(status(orderId)));

        EventEnvelope<PaymentCompleted> late = envelope(PaymentCompleted.TYPE,
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("22.50")));
        publish(PaymentCompleted.TOPIC, orderId, late);
        awaitTrue(() -> processedCount(late.eventId()) == 1);

        assertThat(status(orderId)).as("only PENDING orders change").isEqualTo("FAILED");
    }

    @Test
    void createdOrderIsVisibleAsConfirmedThroughTheApi() throws Exception {
        UUID user = UUID.randomUUID();
        UUID orderId = createOrder(user);
        publish(PaymentCompleted.TOPIC, orderId, envelope(PaymentCompleted.TYPE,
                new PaymentCompleted(orderId, UUID.randomUUID(), new BigDecimal("22.50"))));
        awaitTrue(() -> "CONFIRMED".equals(status(orderId)));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(headers), JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "CONFIRMED");
    }

    private UUID createOrder() {
        return createOrder(UUID.randomUUID());
    }

    private UUID createOrder(UUID user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("items", List.of(
                Map.of("productId", 1, "quantity", 2), Map.of("productId", 2, "quantity", 1)));
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(body, headers), JSON);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private static <T> EventEnvelope<T> envelope(String type, T data) {
        return new EventEnvelope<>(UUID.randomUUID(), type, 1, Instant.now(), data);
    }

    private void publish(String topic, UUID key, EventEnvelope<?> event) throws Exception {
        kafka.send(topic, key.toString(), json.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int processedCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }

    private List<ConsumerRecord<String, String>> awaitRecords(String key, int count) throws InterruptedException {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        awaitTrue(() -> {
            found.addAll(drain(key, Duration.ofMillis(500)));
            return found.size() >= count;
        });
        return found;
    }

    private List<ConsumerRecord<String, String>> drain(String key, Duration duration) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) {
            consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (key.equals(r.key())) {
                    found.add(r);
                }
            });
        }
        return found;
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + DEADLINE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met within " + DEADLINE);
            }
            Thread.sleep(200);
        }
    }
}
