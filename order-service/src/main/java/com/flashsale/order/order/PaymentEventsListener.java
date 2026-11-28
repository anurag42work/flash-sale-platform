package com.flashsale.order.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.PaymentCompleted;
import com.flashsale.common.event.PaymentFailed;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer group order-service (from spring.kafka.consumer.group-id) on payment.completed / payment.failed.
 * Acknowledged only after OrderSagaService committed (at-least-once; the service drops redeliveries).
 * Not acknowledged on an exception: Spring Kafka's default error handler retries, then logs and skips (DLQ: 2.4).
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
        saga.onPaymentCompleted(json.readValue(record.value(), COMPLETED));
        ack.acknowledge();
    }

    @KafkaListener(topics = PaymentFailed.TOPIC)
    void onPaymentFailed(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        saga.onPaymentFailed(json.readValue(record.value(), FAILED));
        ack.acknowledge();
    }
}
