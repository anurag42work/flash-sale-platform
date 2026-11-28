package com.flashsale.common.event;

/**
 * The message can never be processed as it is: malformed JSON, a missing field, an impossible value.
 * Consumers treat it as NOT retryable: it goes straight to the dead-letter topic (incident 004).
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
