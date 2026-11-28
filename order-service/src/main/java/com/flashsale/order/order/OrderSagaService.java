package com.flashsale.order.order;

import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderFailed;
import com.flashsale.common.event.PaymentCompleted;
import com.flashsale.common.event.PaymentFailed;
import com.flashsale.order.outbox.OutboxWriter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The order side of the payment saga: PENDING → CONFIRMED on PaymentCompleted, PENDING → FAILED on PaymentFailed
 * (+ an OrderFailed event so catalog gives the stock back).
 *
 * <p>Each event is handled in ONE transaction: claim the eventId in processed_events, change the status, write the
 * outbox row. A redelivered event finds its id already claimed and does nothing.
 *
 * <p>Status changes are conditional ({@code WHERE status = 'PENDING'}): a late or out-of-order event can't move an
 * order that already left PENDING (e.g. a PaymentCompleted arriving after the order FAILED). The reconciliation job
 * (step 2.6) uses the same transitions, so the job and a late event can never both act on one order.
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    OrderSagaService(JdbcClient jdbc, OutboxWriter outbox, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.tx = tx;
    }

    public void onPaymentCompleted(EventEnvelope<PaymentCompleted> event) {
        UUID orderId = event.data().orderId();
        tx.executeWithoutResult(status -> {
            if (claim(event.eventId(), event.type()) && !confirmInTx(orderId)) {
                log.warn("PaymentCompleted for order {} ignored: order not PENDING (or unknown)", orderId);
            }
        });
    }

    public void onPaymentFailed(EventEnvelope<PaymentFailed> event) {
        UUID orderId = event.data().orderId();
        // e.g. PAYMENT_DECLINED, PAYMENT_PROVIDER_UNAVAILABLE (incident 005): the order shows why payment failed.
        String reason = "PAYMENT_" + event.data().reason();
        tx.executeWithoutResult(status -> {
            if (claim(event.eventId(), event.type()) && !failInTx(orderId, reason)) {
                log.warn("PaymentFailed for order {} ignored: order not PENDING (or unknown)", orderId);
            }
        });
    }

    /** Used by the reconciliation job: same rules as the events, in its own transaction. @return true if changed */
    public boolean confirmIfPending(UUID orderId) {
        return Boolean.TRUE.equals(tx.execute(status -> confirmInTx(orderId)));
    }

    /** Used by the reconciliation job: same rules as the events, in its own transaction. @return true if changed */
    public boolean failIfPending(UUID orderId, String reason) {
        return Boolean.TRUE.equals(tx.execute(status -> failInTx(orderId, reason)));
    }

    private boolean confirmInTx(UUID orderId) {
        return moveFromPending(orderId, OrderStatus.CONFIRMED, null) == 1;
    }

    /** PENDING → FAILED plus an OrderFailed outbox row (catalog releases the stock), or nothing if not PENDING. */
    private boolean failInTx(UUID orderId, String reason) {
        if (moveFromPending(orderId, OrderStatus.FAILED, reason) == 0) {
            return false; // no OrderFailed: the stock is released only once, by the transition that really happened
        }
        List<OrderFailed.Item> items = jdbc.sql(
                        "SELECT product_id, quantity FROM order_items WHERE order_id = :id ORDER BY product_id")
                .param("id", orderId)
                .query((rs, n) -> new OrderFailed.Item(rs.getLong("product_id"), rs.getInt("quantity")))
                .list();
        outbox.add(OrderFailed.TOPIC, orderId, OrderFailed.TYPE, OrderFailed.VERSION,
                new OrderFailed(orderId, reason, items));
        return true;
    }

    /** @return false if this event was already processed (a redelivery) */
    private boolean claim(UUID eventId, String type) {
        int claimed = jdbc.sql("""
                INSERT INTO processed_events (event_id, event_type) VALUES (:id, :type)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("id", eventId)
                .param("type", type)
                .update();
        if (claimed == 0) {
            log.info("Duplicate event {} ({}) skipped", eventId, type);
        }
        return claimed == 1;
    }

    private int moveFromPending(UUID orderId, OrderStatus target, String reason) {
        return jdbc.sql("""
                UPDATE orders SET status = :status, failure_reason = :reason, updated_at = now()
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("status", target.name())
                .param("reason", reason)
                .param("id", orderId)
                .update();
    }
}
