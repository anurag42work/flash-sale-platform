package com.flashsale.order.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.PaymentCompleted;
import com.flashsale.order.TestcontainersConfiguration;
import com.flashsale.order.order.OrderSagaService;
import com.flashsale.order.payment.PaymentClient;
import com.flashsale.order.reconciliation.ReconciliationJob.Result;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Step 2.6: orders stuck in PENDING are settled with payment-service's answer, by one instance at a time.
 * Real Postgres (and Kafka for the context); payment-service is a fake HTTP server. The schedule is off: the test
 * calls runOnce() itself. "Stuck" orders are inserted with a created_at in the past.
 */
@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listeners and outbox relay running and
// would react to the next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest
class ReconciliationIT {

    private static final FakePayments PAYMENTS = new FakePayments();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("payment.base-url", PAYMENTS::baseUrl);
        registry.add("reconciliation.enabled", () -> "false");
        registry.add("reconciliation.stuck-after", () -> "10m");
    }

    @Autowired
    ReconciliationJob job;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    PaymentClient paymentClient;

    @Autowired
    OrderSagaService saga;

    @Autowired
    ReconciliationProperties properties;

    @Autowired
    Clock clock;

    @BeforeEach
    void clean() {
        PAYMENTS.reset();
        jdbc.update("DELETE FROM orders"); // order_items go with ON DELETE CASCADE
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM job_locks");
    }

    @Test
    void stuckOrderWithACompletedPaymentIsConfirmed() {
        UUID order = pendingOrder(60);
        PAYMENTS.payment(order, "COMPLETED", null);

        Result result = job.runOnce();

        assertThat(result).isEqualTo(new Result(true, 1, 1, 0, 0, 0));
        assertThat(status(order)).isEqualTo("CONFIRMED");
        assertThat(orderFailedEvents(order)).isZero();
    }

    @Test
    void stuckOrderWithAFailedPaymentIsFailedAndItsStockReleaseIsQueued() {
        UUID order = pendingOrder(60);
        PAYMENTS.payment(order, "FAILED", "DECLINED");

        Result result = job.runOnce();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(status(order)).isEqualTo("FAILED");
        assertThat(reason(order)).isEqualTo("PAYMENT_DECLINED");
        assertThat(orderFailedEvents(order)).as("OrderFailed in the outbox -> catalog releases the stock").isEqualTo(1);
    }

    @Test
    void stuckOrderWithoutAnyPaymentIsExpiredSoItCanNeverBeChargedLater() {
        UUID order = pendingOrder(60);

        Result result = job.runOnce();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(PAYMENTS.payment(order)).as("payment-service recorded FAILED / EXPIRED").containsExactly("FAILED",
                "EXPIRED");
        assertThat(status(order)).isEqualTo("FAILED");
        assertThat(reason(order)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(orderFailedEvents(order)).isEqualTo(1);
    }

    @Test
    void ordersYoungerThanTheThresholdAreNotTouched() {
        UUID young = pendingOrder(9);    // 9 min: a slow payment, not stuck yet
        UUID decided = pendingOrder(60);
        jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", decided);

        Result result = job.runOnce();

        assertThat(result.checked()).isZero();
        assertThat(PAYMENTS.calls()).isZero();
        assertThat(status(young)).isEqualTo("PENDING");
    }

    @Test
    void paymentServiceDownLeavesTheOrderPendingAndTheNextRunSettlesIt() {
        UUID order = pendingOrder(60);
        PAYMENTS.payment(order, "COMPLETED", null);
        PAYMENTS.down(true);

        Result down = job.runOnce();
        PAYMENTS.down(false);
        Result back = job.runOnce();

        assertThat(down).isEqualTo(new Result(true, 1, 0, 0, 0, 1));
        assertThat(back.confirmed()).isEqualTo(1);
        assertThat(status(order)).isEqualTo("CONFIRMED");
    }

    @Test
    void aPaymentEventArrivingAfterTheJobChangesNothing() {
        UUID order = pendingOrder(60); // no payment -> expired -> FAILED
        job.runOnce();

        // The original payment event shows up late (e.g. replayed from a DLT).
        saga.onPaymentCompleted(new EventEnvelope<>(UUID.randomUUID(), PaymentCompleted.TYPE, 1, Instant.now(),
                new PaymentCompleted(order, UUID.randomUUID(), new BigDecimal("20.00"))));

        assertThat(status(order)).as("only PENDING orders change").isEqualTo("FAILED");
        assertThat(orderFailedEvents(order)).as("stock released once").isEqualTo(1);
    }

    @Test
    void twoInstancesAtTheSameTimeHandleEveryOrderOnce() throws Exception {
        for (int i = 0; i < 20; i++) {
            pendingOrder(60);
        }
        PAYMENTS.delay(50); // a run takes ~1 s, so the two runs really overlap
        // A second instance: its own lock owner, same database and payment-service.
        ReconciliationJob other = new ReconciliationJob(jdbcClient, new JobLock(jdbcClient), paymentClient, saga,
                properties, clock);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService instances = Executors.newFixedThreadPool(2);
        List<Future<Result>> runs = new ArrayList<>();
        for (ReconciliationJob instance : List.of(job, other)) {
            runs.add(instances.submit(() -> {
                go.await();
                return instance.runOnce();
            }));
        }
        go.countDown();
        Result a = runs.get(0).get(30, TimeUnit.SECONDS);
        Result b = runs.get(1).get(30, TimeUnit.SECONDS);
        instances.shutdown();
        System.out.printf("%n=== RECONCILIATION: 2 instances at once -> A %s, B %s, payment calls %d (expected 20) ===%n%n",
                a, b, PAYMENTS.calls());

        assertThat(List.of(a.ran(), b.ran())).as("exactly one instance ran").containsExactlyInAnyOrder(true, false);
        assertThat(a.checked() + b.checked()).isEqualTo(20);
        assertThat(PAYMENTS.calls()).as("each stuck order asked about once").isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'FAILED'", Integer.class))
                .isEqualTo(20);
    }

    @Test
    void lockHeldByAnotherInstanceSkipsTheRunAndAnExpiredLeaseIsTakenOver() {
        pendingOrder(60);
        jdbc.update("INSERT INTO job_locks (name, locked_until, locked_by) VALUES (?, now() + interval '1 hour', 'other')",
                ReconciliationJob.LOCK);

        Result held = job.runOnce();
        jdbc.update("UPDATE job_locks SET locked_until = now() - interval '1 second'"); // 'other' died, lease ran out
        Result takenOver = job.runOnce();

        assertThat(held.ran()).isFalse();
        assertThat(PAYMENTS.calls()).isEqualTo(1); // only the second run asked
        assertThat(takenOver.ran()).isTrue();
        assertThat(jdbc.queryForObject("SELECT locked_by FROM job_locks", String.class)).isNotEqualTo("other");
        assertThat(jdbc.queryForObject("SELECT locked_until <= now() FROM job_locks", Boolean.class))
                .as("released at the end of the run").isTrue();
    }

    /** An order PENDING for {@code minutesAgo} minutes, with one item. */
    private UUID pendingOrder(int minutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, user_id, status, total_amount, created_at, updated_at)
                VALUES (?, ?, 'PENDING', 20.00, now() - (? * interval '1 minute'), now())""",
                id, UUID.randomUUID(), minutesAgo);
        jdbc.update("INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, 1, 2, 10.00)", id);
        return id;
    }

    private String status(UUID order) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order);
    }

    private String reason(UUID order) {
        return jdbc.queryForObject("SELECT failure_reason FROM orders WHERE id = ?", String.class, order);
    }

    private int orderFailedEvents(UUID order) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'OrderFailed'",
                Integer.class, order);
    }
}
