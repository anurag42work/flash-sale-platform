package com.flashsale.common.web;

/**
 * HTTP header names shared by the gateway and the services behind it.
 * The gateway strips client-sent identity headers and sets them from the validated JWT.
 */
public final class Headers {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLES = "X-User-Roles";
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private Headers() {
    }
}
