package com.flashsale.common.event;

import java.util.UUID;

/**
 * Published by payment-service when the provider declined the charge; order-service fails the order and
 * gives the stock back.
 *
 * @param reason short machine-readable code, e.g. "DECLINED"
 */
public record PaymentFailed(UUID orderId, UUID paymentId, String reason) {

    public static final String TOPIC = "payment.failed";
    public static final String TYPE = "PaymentFailed";
    public static final int VERSION = 1;
}
