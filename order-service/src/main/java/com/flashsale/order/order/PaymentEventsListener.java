package com.flashsale.order.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.InvalidEventException;
import com.flashsale.common.event.PaymentCompleted;
import com.flashsale.common.event.PaymentFailed;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer group order-service (from spring.kafka.consumer.group-id) on payment.completed / payment.failed.
 * Acknowledged only after OrderSagaService committed (at-least-once; the service drops redeliveries).
 * On an exception the record is not acknowledged; the error handler (config/KafkaErrorConfig) retries with backoff
 * and then parks it on payment.*.DLT. Malformed JSON and invalid events go to the DLT at once.
 */
@Component
class PaymentEventsListener {

    private static final TypeReference<EventEnvelope<PaymentCompleted>> COMPLETED = new TypeReference<>() {
    };
    private static final TypeReference<EventEnvelope<PaymentFailed>> FAILED = new TypeReference<>() {
    };

    private final ObjectMapper json;
    private final OrderSagaService saga;

    PaymentEventsListener(ObjectMapper json, OrderSagaService saga) {
        this.json = json;
        this.saga = saga;
    }

    @KafkaListener(topics = PaymentCompleted.TOPIC)
    void onPaymentCompleted(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        EventEnvelope<PaymentCompleted> event = json.readValue(record.value(), COMPLETED)
                .requireValid(PaymentCompleted.TYPE);
        requireOrderId(event.data().orderId());
        saga.onPaymentCompleted(event);
        ack.acknowledge();
    }

    @KafkaListener(topics = PaymentFailed.TOPIC)
    void onPaymentFailed(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        EventEnvelope<PaymentFailed> event = json.readValue(record.value(), FAILED).requireValid(PaymentFailed.TYPE);
        requireOrderId(event.data().orderId());
        requireReasonCode(event.data().reason());
        saga.onPaymentFailed(event);
        ack.acknowledge();
    }

    /** Stored as "PAYMENT_" + reason in orders.failure_reason (varchar 64): a short upper-case code only. */
    private static void requireReasonCode(String reason) {
        if (reason == null || !reason.matches("[A-Z_]{1,40}")) {
            throw new InvalidEventException("reason must be an upper-case code, was " + reason);
        }
    }

    private static void requireOrderId(UUID orderId) {
        if (orderId == null) {
            throw new InvalidEventException("orderId is missing");
        }
    }
}
