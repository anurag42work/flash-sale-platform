package com.flashsale.common.event;

import java.util.List;
import java.util.UUID;

/**
 * Published by order-service when an order moves PENDING → FAILED (e.g. payment declined). catalog-service reacts
 * by releasing the order's reserved stock. A fact ("the order failed"), not a command, so other services can react
 * to it too.
 *
 * @param reason short machine-readable code, e.g. "PAYMENT_DECLINED"
 */
public record OrderFailed(UUID orderId, String reason, List<Item> items) {

    public static final String TOPIC = "order.failed";
    public static final String TYPE = "OrderFailed";
    public static final int VERSION = 1;

    public record Item(long productId, int quantity) {
    }
}
