package com.flashsale.payment.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderCreated;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer group payment-service on order.created. The offset is acknowledged only after PaymentService has
 * committed its transaction, so a crash in between means the event is delivered again (at-least-once), and
 * PaymentService's idempotency drops the copy.
 *
 * <p>An exception (bad JSON, DB down) is NOT acknowledged: Spring Kafka's default error handler retries the record
 * a few times, then logs and skips it. Dead-letter topic: step 2.4 (incident 004).
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
        payments.handle(json.readValue(record.value(), TYPE));
        ack.acknowledge();
    }
}
