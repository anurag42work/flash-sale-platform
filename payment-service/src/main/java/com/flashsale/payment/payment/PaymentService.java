package com.flashsale.payment.payment;

import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderCreated;
import com.flashsale.common.event.PaymentCompleted;
import com.flashsale.common.event.PaymentFailed;
import com.flashsale.payment.outbox.OutboxWriter;
import com.flashsale.payment.provider.MockPaymentProvider.ChargeResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Charges an order once per OrderCreated event, even when Kafka delivers the event more than once.
 *
 * <p>Three guards against duplicates, cheapest first:
 * <ol>
 *   <li>a read of processed_events before calling the provider (the usual redelivery case),</li>
 *   <li>the provider's idempotency key = orderId (a duplicate that slips past 1 is not charged twice),</li>
 *   <li>in the transaction: processed_events PK and payments UNIQUE (order_id) (two copies at the same time).</li>
 * </ol>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final JdbcClient jdbc;
    private final ResilientPaymentGateway gateway;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    PaymentService(JdbcClient jdbc, ResilientPaymentGateway gateway, OutboxWriter outbox, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.outbox = outbox;
        this.tx = tx;
    }

    public void handle(EventEnvelope<OrderCreated> event) {
        OrderCreated order = event.data();
        if (alreadyProcessed(event.eventId())) {
            log.info("Duplicate event {} for order {} skipped", event.eventId(), order.orderId());
            return;
        }

        // Outside the DB transaction: no connection is held while the (possibly slow) provider works. Through the
        // gateway: timeout, retry, circuit breaker, bulkhead (incident 005); "unavailable" comes back as a decline.
        ChargeResult result = gateway.charge(order.orderId(), order.totalAmount());

        tx.executeWithoutResult(status -> {
            int claimed = jdbc.sql("""
                    INSERT INTO processed_events (event_id, event_type) VALUES (:id, :type)
                    ON CONFLICT (event_id) DO NOTHING
                    """)
                    .param("id", event.eventId())
                    .param("type", event.type())
                    .update();
            if (claimed == 0) {
                log.info("Event {} was processed concurrently, skipped", event.eventId());
                return;
            }
            UUID paymentId = UUID.randomUUID();
            int saved = jdbc.sql("""
                    INSERT INTO payments (id, order_id, amount, status, failure_reason)
                    VALUES (:id, :orderId, :amount, :status, :reason)
                    ON CONFLICT (order_id) DO NOTHING
                    """)
                    .param("id", paymentId)
                    .param("orderId", order.orderId())
                    .param("amount", order.totalAmount())
                    .param("status", result.approved() ? "COMPLETED" : "FAILED")
                    .param("reason", result.failureReason())
                    .update();
            if (saved == 0) {
                // Another event id for an order we already handled: keep the first payment and its event.
                log.warn("Order {} already has a payment; event {} ignored", order.orderId(), event.eventId());
                return;
            }
            if (result.approved()) {
                outbox.add(PaymentCompleted.TOPIC, order.orderId(), PaymentCompleted.TYPE, PaymentCompleted.VERSION,
                        new PaymentCompleted(order.orderId(), paymentId, order.totalAmount()));
            } else {
                outbox.add(PaymentFailed.TOPIC, order.orderId(), PaymentFailed.TYPE, PaymentFailed.VERSION,
                        new PaymentFailed(order.orderId(), paymentId, result.failureReason()));
            }
        });
    }

    private boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id = :id)")
                .param("id", eventId)
                .query(Boolean.class)
                .single();
    }
}
