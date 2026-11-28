package com.flashsale.order.reconciliation;

import com.flashsale.order.order.OrderSagaService;
import com.flashsale.order.payment.PaymentClient;
import com.flashsale.order.payment.PaymentClient.PaymentStatus;
import com.flashsale.order.payment.PaymentServiceUnavailableException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds orders stuck in PENDING (their payment event never arrived: payment-service down for long, an event parked
 * on a DLT, a blocked relay) and settles them with payment-service's answer (step 2.6):
 *
 * <ul>
 *   <li>payment COMPLETED → order CONFIRMED,</li>
 *   <li>payment FAILED → order FAILED (PAYMENT_&lt;reason&gt;) + OrderFailed, so catalog releases the stock,</li>
 *   <li>no payment → payment-service records it as FAILED / EXPIRED (so it can't be charged later) → same as FAILED.</li>
 * </ul>
 *
 * <p>Only one instance runs it at a time (JobLock). Correctness doesn't depend on the lock, though: every change is
 * the saga's conditional PENDING transition and payment-service's answer is idempotent, so a double run would do
 * nothing twice. The lock only avoids doing the same work in parallel.
 */
@Component
public class ReconciliationJob {

    static final String LOCK = "order-reconciliation";
    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final JdbcClient jdbc;
    private final JobLock lock;
    private final PaymentClient payments;
    private final OrderSagaService saga;
    private final ReconciliationProperties properties;
    private final Clock clock;

    ReconciliationJob(JdbcClient jdbc, JobLock lock, PaymentClient payments, OrderSagaService saga,
            ReconciliationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.payments = payments;
        this.saga = saga;
        this.properties = properties;
        this.clock = clock;
    }

    /** What one run did. {@code ran} is false when another instance held the lock. */
    public record Result(boolean ran, int checked, int confirmed, int failed, int unchanged, int unreachable) {

        static Result skipped() {
            return new Result(false, 0, 0, 0, 0, 0);
        }
    }

    @Scheduled(fixedDelayString = "${reconciliation.interval}", initialDelayString = "${reconciliation.interval}")
    void scheduled() {
        if (properties.enabled()) {
            runOnce();
        }
    }

    public Result runOnce() {
        if (!lock.tryAcquire(LOCK, properties.lockLease())) {
            log.debug("Reconciliation skipped: another instance holds the lock");
            return Result.skipped();
        }
        try {
            return reconcile();
        } finally {
            lock.release(LOCK);
        }
    }

    private Result reconcile() {
        List<StuckOrder> stuck = jdbc.sql("""
                SELECT id, total_amount FROM orders
                WHERE status = 'PENDING' AND created_at < :cutoff
                ORDER BY created_at
                LIMIT :batch
                """)
                .param("cutoff", Timestamp.from(clock.instant().minus(properties.stuckAfter())))
                .param("batch", properties.batchSize())
                .query(StuckOrder.class)
                .list();
        int confirmed = 0;
        int failed = 0;
        int unchanged = 0;
        int unreachable = 0;
        for (StuckOrder order : stuck) {
            PaymentStatus payment;
            try {
                payment = payments.finalStatus(order.id(), order.totalAmount());
            } catch (PaymentServiceUnavailableException e) {
                unreachable++; // stays PENDING; the next run asks again
                log.warn("Reconciliation: {}", e.getMessage());
                continue;
            }
            switch (payment.status()) {
                case "COMPLETED" -> {
                    if (saga.confirmIfPending(order.id())) {
                        confirmed++;
                    } else {
                        unchanged++; // a payment event got there first
                    }
                }
                case "FAILED" -> {
                    if (saga.failIfPending(order.id(), "PAYMENT_" + reasonCode(payment.reason()))) {
                        failed++;
                    } else {
                        unchanged++;
                    }
                }
                default -> {
                    unchanged++;
                    log.error("Reconciliation: unknown payment status {} for order {}", payment.status(), order.id());
                }
            }
        }
        Result result = new Result(true, stuck.size(), confirmed, failed, unchanged, unreachable);
        if (!stuck.isEmpty()) {
            log.info("Reconciliation: {}", result);
        }
        return result;
    }

    /** Stored as PAYMENT_&lt;reason&gt; in orders.failure_reason (varchar 64): accept only a short code. */
    private static String reasonCode(String reason) {
        return reason != null && reason.matches("[A-Z_]{1,40}") ? reason : "UNKNOWN";
    }

    record StuckOrder(UUID id, BigDecimal totalAmount) {
    }
}
