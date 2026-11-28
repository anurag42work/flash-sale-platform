package com.flashsale.payment.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.InvalidEventException;
import com.flashsale.common.event.OrderCreated;
import java.math.BigDecimal;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer group payment-service on order.created. The offset is acknowledged only after PaymentService has
 * committed its transaction, so a crash in between means the event is delivered again (at-least-once), and
 * PaymentService's idempotency drops the copy.
 *
 * <p>On an exception the record is not acknowledged; the error handler (config/KafkaErrorConfig) retries with backoff
 * and then parks it on order.created.DLT. Malformed JSON and invalid events go to the DLT at once.
 */
@Component
class OrderCreatedListener {

    private static final TypeReference<EventEnvelope<OrderCreated>> TYPE = new TypeReference<>() {
    };

    private final ObjectMapper json;
    private final PaymentService payments;

    OrderCreatedListener(ObjectMapper json, PaymentService payments) {
        this.json = json;
        this.payments = payments;
    }

    // Group id from spring.kafka.consumer.group-id (payment-service), so tests can give each context its own.
    @KafkaListener(topics = OrderCreated.TOPIC)
    void onOrderCreated(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        EventEnvelope<OrderCreated> event = json.readValue(record.value(), TYPE).requireValid(OrderCreated.TYPE);
        validate(event.data());
        payments.handle(event);
        ack.acknowledge();
    }

    /** Checks what PaymentService relies on; anything else is a bug upstream, not something a retry can fix. */
    static void validate(OrderCreated order) {
        if (order.orderId() == null) {
            throw new InvalidEventException("orderId is missing");
        }
        if (order.totalAmount() == null || order.totalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidEventException("totalAmount must be > 0 for order " + order.orderId());
        }
        if (order.items() == null || order.items().isEmpty()) {
            throw new InvalidEventException("items are missing for order " + order.orderId());
        }
    }
}
