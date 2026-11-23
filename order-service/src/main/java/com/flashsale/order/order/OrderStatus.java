package com.flashsale.order.order;

/** PENDING: stock reserved, waiting for payment (Day 2 saga moves it to CONFIRMED or FAILED). */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FAILED
}
