package com.flashsale.catalog.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.InvalidEventException;
import com.flashsale.common.event.OrderFailed;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer group catalog-service (from spring.kafka.consumer.group-id) on order.failed: releases the order's stock.
 * Acknowledged only after InventoryService committed (at-least-once; the service drops redeliveries).
 * On an exception the record is not acknowledged; the error handler (config/KafkaErrorConfig) retries with backoff
 * and then parks it on order.failed.DLT. Malformed JSON and invalid events go to the DLT at once.
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
        EventEnvelope<OrderFailed> event = json.readValue(record.value(), TYPE).requireValid(OrderFailed.TYPE);
        validate(event.data());
        inventory.releaseForFailedOrder(event);
        ack.acknowledge();
    }

    /** A bad quantity would add (or with a negative value, remove) the wrong amount of stock: reject the whole event. */
    static void validate(OrderFailed order) {
        if (order.orderId() == null) {
            throw new InvalidEventException("orderId is missing");
        }
        if (order.items() == null) {
            throw new InvalidEventException("items are missing for order " + order.orderId());
        }
        for (OrderFailed.Item item : order.items()) {
            if (item == null || item.quantity() <= 0) {
                throw new InvalidEventException("every item needs a quantity > 0 (order " + order.orderId() + ")");
            }
        }
    }
}
