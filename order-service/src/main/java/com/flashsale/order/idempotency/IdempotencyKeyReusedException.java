package com.flashsale.order.idempotency;

/** 422: the key was already used for a different request body. A client bug, not a retry. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used with a different request body");
    }
}
