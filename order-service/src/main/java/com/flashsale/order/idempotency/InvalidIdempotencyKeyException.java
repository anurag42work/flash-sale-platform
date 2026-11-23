package com.flashsale.order.idempotency;

/** 400: the Idempotency-Key header is missing or malformed. */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key header is required: 1-64 characters of letters, digits, '-' or '_' (a UUID works)");
    }
}
