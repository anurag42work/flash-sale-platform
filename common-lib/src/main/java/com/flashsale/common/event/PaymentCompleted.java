package com.flashsale.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Published by payment-service when the provider accepted the charge; order-service confirms the order. */
public record PaymentCompleted(UUID orderId, UUID paymentId, BigDecimal amount) {

    public static final String TOPIC = "payment.completed";
    public static final String TYPE = "PaymentCompleted";
    public static final int VERSION = 1;
}
