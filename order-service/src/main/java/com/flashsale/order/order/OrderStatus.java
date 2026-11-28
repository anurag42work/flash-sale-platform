package com.flashsale.order.order;

/** PENDING: stock reserved, waiting for payment. The saga (OrderSagaService) moves it to CONFIRMED or FAILED, once. */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FAILED
}
