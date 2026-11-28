package com.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.OrderCreated;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Incident 003: dual write. Creating an order writes to two systems, the orders table and Kafka, with no shared
 * transaction. An order that is committed must always get its OrderCreated event, even if the first attempt to
 * publish fails.
 */
@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listeners and outbox relay running and
// would react to the next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderEventsIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    private static final FakeCatalog CATALOG = new FakeCatalog();
    /** How long a committed order may wait for its event. Generous: the fix (2.2) polls every second or so. */
    private static final Duration EVENT_DEADLINE = Duration.ofSeconds(15);

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

    // Boot declares the template as KafkaTemplate<?, ?>; a spy lets one test make send() fail.
    @MockitoSpyBean
    KafkaTemplate<?, ?> kafka;

    KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        CATALOG.reset();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(OrderCreated.TOPIC));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void committedOrderPublishesOneOrderCreatedEventKeyedByOrderId() throws Exception {
        CATALOG.product(1, "10.00", 5);
        UUID user = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = createOrder(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String orderId = (String) response.getBody().get("id");
        List<ConsumerRecord<String, String>> events = eventsFor(orderId);
        assertThat(events).hasSize(1);
        JsonNode envelope = json.readTree(events.get(0).value());
        assertThat(envelope.get("type").asText()).isEqualTo("OrderCreated");
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        assertThat(envelope.get("occurredAt").asText()).isNotBlank();
        String eventId = envelope.get("eventId").asText();
        assertThat(jdbc.queryForObject("SELECT aggregate_id::text FROM outbox WHERE id = ?::uuid", String.class,
                eventId)).as("eventId = outbox row id").isEqualTo(orderId);
        JsonNode event = envelope.get("data");
        assertThat(event.get("orderId").asText()).isEqualTo(orderId);
        assertThat(event.get("userId").asText()).isEqualTo(user.toString());
        assertThat(event.get("totalAmount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(event.get("items").get(0).get("productId").asLong()).isEqualTo(1);
    }

    @Test
    void committedOrderStillGetsItsEventWhenThePublishFailsAfterTheCommit() {
        CATALOG.product(1, "10.00", 5);
        // The first send fails (as when Kafka is down or the process dies right after the DB commit);
        // later sends work, so anything that retries would succeed.
        doThrow(new KafkaException("simulated: broker unreachable")).doCallRealMethod()
                .when(kafka).send(any(String.class), any(), any());

        ResponseEntity<Map<String, Object>> response = createOrder(UUID.randomUUID());

        String orderId = (String) response.getBody().get("id");
        String status = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?::uuid", String.class, orderId);
        List<ConsumerRecord<String, String>> events = eventsFor(orderId);
        System.out.printf("%n=== INCIDENT 003: HTTP %d, order %s in DB as %s, OrderCreated events after %d s: %d"
                + " (expected 1) ===%n%n", response.getStatusCode().value(), orderId, status,
                EVENT_DEADLINE.toSeconds(), events.size());

        assertThat(status).as("the order row is committed").isEqualTo("PENDING");
        assertThat(events).as("a committed order must get exactly one OrderCreated event").hasSize(1);
    }

    /** Reads the topic until the deadline (or shortly after the first match, to catch duplicates). */
    private List<ConsumerRecord<String, String>> eventsFor(String orderId) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + EVENT_DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (orderId.equals(record.key())) {
                    found.add(record);
                }
            }
            if (!found.isEmpty()) {
                deadline = Math.min(deadline, System.nanoTime() + Duration.ofSeconds(2).toNanos());
            }
        }
        return found;
    }

    private ResponseEntity<Map<String, Object>> createOrder(UUID user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("items", List.of(Map.of("productId", 1, "quantity", 2)));
        return rest.exchange("/api/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JSON);
    }
}
