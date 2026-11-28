package com.flashsale.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderCreated;
import com.flashsale.common.event.PaymentCompleted;
import com.flashsale.common.event.PaymentFailed;
import com.flashsale.payment.provider.MockPaymentProvider;
import com.flashsale.payment.provider.MockPaymentProvider.ChargeResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * order.created in → one payment row + payment.completed / payment.failed out, against real Postgres and Kafka.
 * Kafka is at-least-once, so the same event delivered twice must be charged and published only once.
 */
@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listener and outbox relay running and
// would react to the next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest
@TestPropertySource(properties = {
        "payment.provider.latency-ms=0",
        "payment.provider.failure-rate-percent=0" // declines are injected per test with the spy
})
class PaymentFlowIT {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @MockitoSpyBean
    MockPaymentProvider provider;

    KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(PaymentCompleted.TOPIC, PaymentFailed.TOPIC));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void approvedChargeSavesPaymentAndPublishesPaymentCompleted() throws Exception {
        EventEnvelope<OrderCreated> event = orderCreated(UUID.randomUUID(), UUID.randomUUID(), "20.00");

        publish(event);

        String orderId = event.data().orderId().toString();
        List<ConsumerRecord<String, String>> out = awaitRecords(orderId, 1);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).topic()).isEqualTo(PaymentCompleted.TOPIC);
        JsonNode envelope = json.readTree(out.get(0).value());
        assertThat(envelope.get("type").asText()).isEqualTo(PaymentCompleted.TYPE);
        JsonNode data = envelope.get("data");
        assertThat(data.get("orderId").asText()).isEqualTo(orderId);
        assertThat(data.get("amount").decimalValue()).isEqualByComparingTo("20.00");

        Map<String, Object> payment = jdbc.queryForMap("SELECT id::text AS id, status, amount FROM payments WHERE order_id = ?::uuid",
                orderId);
        assertThat(payment.get("status")).isEqualTo("COMPLETED");
        assertThat(data.get("paymentId").asText()).isEqualTo(payment.get("id"));
    }

    @Test
    void declinedChargeSavesFailedPaymentAndPublishesPaymentFailed() throws Exception {
        EventEnvelope<OrderCreated> event = orderCreated(UUID.randomUUID(), UUID.randomUUID(), "20.00");
        doReturn(new ChargeResult(false, "DECLINED")).when(provider).charge(eq(event.data().orderId()), any());

        publish(event);

        String orderId = event.data().orderId().toString();
        List<ConsumerRecord<String, String>> out = awaitRecords(orderId, 1);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).topic()).isEqualTo(PaymentFailed.TOPIC);
        assertThat(json.readTree(out.get(0).value()).get("data").get("reason").asText()).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT status || ':' || failure_reason FROM payments WHERE order_id = ?::uuid",
                String.class, orderId)).isEqualTo("FAILED:DECLINED");
    }

    @Test
    void sameEventDeliveredTwiceIsChargedAndPublishedOnce() throws Exception {
        EventEnvelope<OrderCreated> event = orderCreated(UUID.randomUUID(), UUID.randomUUID(), "20.00");
        String orderId = event.data().orderId().toString();

        publish(event);
        publish(event); // what a redelivery looks like: same eventId, same key, same partition, after the first

        awaitTrue(() -> processed(event.eventId()) == 1);
        List<ConsumerRecord<String, String>> out = awaitRecords(orderId, 1);
        out.addAll(drain(orderId, Duration.ofSeconds(3))); // a second publish would show up here
        int payments = jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?::uuid", Integer.class, orderId);
        System.out.printf("%n=== SAGA: same OrderCreated delivered 2x -> payments=%d, payment events=%d, provider calls=1 expected ===%n%n",
                payments, out.size());

        assertThat(payments).isEqualTo(1);
        assertThat(out).hasSize(1);
        verify(provider, times(1)).charge(eq(event.data().orderId()), any()); // guard 1: the processed_events read
    }

    @Test
    void twoDifferentEventsForTheSameOrderStillGiveOnePayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        EventEnvelope<OrderCreated> first = orderCreated(orderId, user, "20.00");
        EventEnvelope<OrderCreated> second = orderCreated(orderId, user, "20.00"); // new eventId, same order

        publish(first);
        publish(second); // processed_events can't catch this one; payments UNIQUE (order_id) does

        awaitTrue(() -> processed(first.eventId()) == 1 && processed(second.eventId()) == 1);
        List<ConsumerRecord<String, String>> out = awaitRecords(orderId.toString(), 1);
        out.addAll(drain(orderId.toString(), Duration.ofSeconds(3)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?::uuid", Integer.class,
                orderId.toString())).isEqualTo(1);
        assertThat(out).as("one payment event for the order").hasSize(1);
    }

    private EventEnvelope<OrderCreated> orderCreated(UUID orderId, UUID userId, String total) {
        OrderCreated data = new OrderCreated(orderId, userId, new BigDecimal(total),
                List.of(new OrderCreated.Item(1, 2, new BigDecimal(total).divide(BigDecimal.TWO))));
        return new EventEnvelope<>(UUID.randomUUID(), OrderCreated.TYPE, OrderCreated.VERSION, Instant.now(), data);
    }

    private void publish(EventEnvelope<OrderCreated> event) throws Exception {
        kafka.send(OrderCreated.TOPIC, event.data().orderId().toString(), json.writeValueAsString(event))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private int processed(UUID eventId) {
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
