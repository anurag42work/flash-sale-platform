package com.flashsale.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderCreated;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Incident 005: the payment provider becomes slow (5 s per charge) during a flash sale. 50 orders arrive.
 * Every order must get a decision (COMPLETED or FAILED) quickly, so its stock is either sold or given back;
 * a slow provider must not leave orders PENDING behind an ever-growing queue.
 */
@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listener and outbox relay running and
// would react to the next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest
@TestPropertySource(properties = {
        "payment.provider.latency-ms=5000",
        "payment.provider.failure-rate-percent=0"
})
class SlowProviderIT {

    private static final int ORDERS = 50;
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Test
    void everyOrderGetsAPaymentDecisionWithinTheDeadlineWhenTheProviderIsSlow() throws Exception {
        String batch = UUID.randomUUID().toString(); // userId marks this test's orders
        UUID user = UUID.fromString(batch);
        long start = System.nanoTime();
        for (int i = 0; i < ORDERS; i++) {
            UUID orderId = UUID.randomUUID();
            OrderCreated data = new OrderCreated(orderId, user, new BigDecimal("20.00"),
                    List.of(new OrderCreated.Item(1, 2, new BigDecimal("10.00"))));
            EventEnvelope<OrderCreated> event = new EventEnvelope<>(UUID.randomUUID(), OrderCreated.TYPE,
                    OrderCreated.VERSION, Instant.now(), data);
            kafka.send(OrderCreated.TOPIC, orderId.toString(), json.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        }

        int decided = 0;
        long end = start + DEADLINE.toNanos();
        while (System.nanoTime() < end && decided < ORDERS) {
            Thread.sleep(500);
            decided = decided();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        List<Map<String, Object>> byStatus = jdbc.queryForList("""
                SELECT p.status, coalesce(p.failure_reason, '-') AS reason, count(*) AS n
                FROM payments p GROUP BY p.status, p.failure_reason ORDER BY 1, 2""");
        System.out.printf("%n=== INCIDENT 005: provider 5000 ms, %d orders -> decided %d, still PENDING %d after %d ms;"
                + " payments by status %s ===%n%n", ORDERS, decided, ORDERS - decided, elapsedMillis, byStatus);

        assertThat(decided).as("orders with a payment decision within " + DEADLINE.toSeconds() + " s").isEqualTo(ORDERS);
    }

    private int decided() {
        // payments has no user id; this test's context has its own database, so every row belongs to this test.
        return jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class);
    }
}
