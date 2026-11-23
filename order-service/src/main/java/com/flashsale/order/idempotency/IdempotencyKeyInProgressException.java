package com.flashsale.order.idempotency;

/** 409: a request with this key is still being processed. The client should retry a little later. */
public class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException() {
        super("A request with this Idempotency-Key is still in progress, retry later");
    }
}
