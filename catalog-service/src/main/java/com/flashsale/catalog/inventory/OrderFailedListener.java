package com.flashsale.catalog.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderFailed;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer group catalog-service (from spring.kafka.consumer.group-id) on order.failed: releases the order's stock.
 * Acknowledged only after InventoryService committed (at-least-once; the service drops redeliveries).
 * Not acknowledged on an exception: Spring Kafka's default error handler retries, then logs and skips (DLQ: 2.4).
 */
@Component
class OrderFailedListener {

    private static final TypeReference<EventEnvelope<OrderFailed>> TYPE = new TypeReference<>() {
    };

    private final ObjectMapper json;
    private final InventoryService inventory;

    OrderFailedListener(ObjectMapper json, InventoryService inventory) {
        this.json = json;
        this.inventory = inventory;
    }

    @KafkaListener(topics = OrderFailed.TOPIC)
    void onOrderFailed(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        inventory.releaseForFailedOrder(json.readValue(record.value(), TYPE));
        ack.acknowledge();
    }
}
