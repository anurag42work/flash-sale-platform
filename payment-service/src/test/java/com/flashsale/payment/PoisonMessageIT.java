package com.flashsale.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderCreated;
import com.flashsale.payment.payment.PaymentService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Incident 004: a message the consumer can't process. Two kinds:
 * <ul>
 *   <li>poison: bad data (malformed JSON). Retrying can never help; it must be parked at once, and the healthy
 *       messages behind it must keep flowing,</li>
 *   <li>transient: the message is fine but processing fails for a while (DB outage). It must not be lost: retry a
 *       few times, then park it so it can be replayed once the outage is over.</li>
 * </ul>
 * "Parked" = on the dead-letter topic {@code order.created.DLT}, with the error in the headers.
 */
@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listener and outbox relay running and
// would react to the next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest
@TestPropertySource(properties = {
        "payment.provider.latency-ms=0",
        "payment.provider.failure-rate-percent=0"
})
class PoisonMessageIT {

    private static final String DLT = OrderCreated.TOPIC + ".DLT";
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    ObjectMapper json; // spied to count how often the poison message is parsed (= delivery attempts)

    @MockitoSpyBean
    PaymentService payments;

    KafkaConsumer<String, String> dltConsumer;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Don't let this test consumer create the DLT with broker defaults (1 partition) before the app declares it.
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        dltConsumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        dltConsumer.subscribe(List.of(DLT));
    }

    @AfterEach
    void closeConsumer() {
        dltConsumer.close();
    }

    @Test
    void malformedMessageGoesStraightToTheDltAndTheNextMessageIsStillProcessed() throws Exception {
        String key = UUID.randomUUID().toString();
        String poison = "{\"eventId\": \"not-even-json"; // truncated JSON
        EventEnvelope<OrderCreated> valid = orderCreated(UUID.fromString(key));

        long start = System.nanoTime();
        send(key, poison);
        send(key, json.writeValueAsString(valid)); // same key = same partition, right behind the poison message

        awaitTrue(() -> paymentExists(valid.data().orderId()));
        long healthyAfterMillis = (System.nanoTime() - start) / 1_000_000;
        List<ConsumerRecord<String, String>> parked = dltRecords(key, Duration.ofSeconds(5));
        long attempts = parseAttempts(poison);
        System.out.printf("%n=== INCIDENT 004 (poison): attempts=%d (expected 1), on DLT=%d (expected 1),"
                + " next message processed after %d ms ===%n%n", attempts, parked.size(), healthyAfterMillis);

        assertThat(parked).as("poison message parked on " + DLT).hasSize(1);
        assertThat(parked.get(0).value()).isEqualTo(poison);
        // exception-fqcn holds Spring's wrapper (ListenerExecutionFailedException); the real error is the cause.
        assertThat(header(parked.get(0), "kafka_dlt-exception-cause-fqcn")).contains("Json");
        assertThat(header(parked.get(0), "kafka_dlt-original-topic")).isEqualTo(OrderCreated.TOPIC);
        assertThat(attempts).as("bad data is not retried").isEqualTo(1);
    }

    @Test
    void wellFormedButInvalidEventGoesStraightToTheDlt() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderCreated zeroTotal = new OrderCreated(orderId, UUID.randomUUID(), BigDecimal.ZERO,
                List.of(new OrderCreated.Item(1, 1, BigDecimal.ZERO)));
        EventEnvelope<OrderCreated> event = new EventEnvelope<>(UUID.randomUUID(), OrderCreated.TYPE,
                OrderCreated.VERSION, Instant.now(), zeroTotal);

        send(orderId.toString(), json.writeValueAsString(event));

        List<ConsumerRecord<String, String>> parked = dltRecords(orderId.toString(), Duration.ofSeconds(5));
        assertThat(parked).hasSize(1);
        assertThat(header(parked.get(0), "kafka_dlt-exception-cause-fqcn"))
                .isEqualTo("com.flashsale.common.event.InvalidEventException");
        assertThat(header(parked.get(0), "kafka_dlt-exception-message")).contains("totalAmount");
        assertThat(paymentExists(orderId)).isFalse();
        verify(payments, never()).handle(any()); // rejected before any processing
    }

    @Test
    void eventFailingLongerThanTheRetriesIsParkedOnTheDltAndCanBeReplayed() throws Exception {
        EventEnvelope<OrderCreated> event = orderCreated(UUID.randomUUID());
        String key = event.data().orderId().toString();
        // Simulated DB outage for this event: every attempt in the next 8 s fails, later attempts would work.
        long outageEnd = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        doAnswer(call -> {
            EventEnvelope<?> arg = call.getArgument(0);
            if (arg.eventId().equals(event.eventId()) && System.nanoTime() < outageEnd) {
                throw new TransientDataAccessResourceException("simulated: database unavailable");
            }
            return call.callRealMethod();
        }).when(payments).handle(any());

        send(key, json.writeValueAsString(event));

        List<ConsumerRecord<String, String>> parked = dltRecords(key, Duration.ofSeconds(15));
        long attempts = mockingDetails(payments).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("handle")
                        && ((EventEnvelope<?>) i.getArgument(0)).eventId().equals(event.eventId()))
                .count();
        System.out.printf("%n=== INCIDENT 004 (transient): attempts=%d, payment created=%s, on DLT=%d (expected 1) ===%n%n",
                attempts, paymentExists(event.data().orderId()), parked.size());
        assertThat(parked).as("event not lost: parked on " + DLT).hasSize(1);

        // After the outage: replay = publish the parked value again to the original topic, same key.
        awaitTrue(() -> System.nanoTime() > outageEnd);
        send(key, parked.get(0).value());
        awaitTrue(() -> paymentExists(event.data().orderId()));
    }

    private long parseAttempts(String value) {
        return mockingDetails(json).getInvocations().stream()
                // only the listener's call readValue(String, TypeReference): Jackson calls other overloads internally
                .filter(i -> i.getMethod().getName().equals("readValue") && value.equals(i.getArgument(0))
                        && i.getArgument(1) instanceof com.fasterxml.jackson.core.type.TypeReference)
                .count();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private boolean paymentExists(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Integer.class, orderId) > 0;
    }

    private EventEnvelope<OrderCreated> orderCreated(UUID orderId) {
        OrderCreated data = new OrderCreated(orderId, UUID.randomUUID(), new BigDecimal("20.00"),
                List.of(new OrderCreated.Item(1, 2, new BigDecimal("10.00"))));
        return new EventEnvelope<>(UUID.randomUUID(), OrderCreated.TYPE, OrderCreated.VERSION, Instant.now(), data);
    }

    private void send(String key, String value) throws Exception {
        kafka.send(OrderCreated.TOPIC, key, value).get(10, TimeUnit.SECONDS);
    }

    /** Collects DLT records with this key for the given time. */
    private List<ConsumerRecord<String, String>> dltRecords(String key, Duration duration) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) {
            dltConsumer.poll(Duration.ofMillis(200)).forEach(r -> {
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
