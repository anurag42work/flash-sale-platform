package com.flashsale.common.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Published by order-service when an order is saved as PENDING; payment-service reacts to it (Day 2).
 * Sent as the {@code data} of an {@link EventEnvelope} (JSON) on {@link #TOPIC}, keyed by orderId so all events
 * of one order stay in one partition (in order).
 */
public record OrderCreated(UUID orderId, UUID userId, BigDecimal totalAmount, List<Item> items) {

    public static final String TOPIC = "order.created";
    public static final String TYPE = "OrderCreated";
    public static final int VERSION = 1;

    public record Item(long productId, int quantity, BigDecimal unitPrice) {
    }
}
