package com.flashsale.order.web;

import java.util.UUID;

/**
 * Reads the user id the gateway put in X-User-Id. The gateway removes any client-sent value and sets it from a
 * validated JWT, so the service can trust it (internal network only).
 */
public final class RequestUser {

    private RequestUser() {
    }

    public static UUID requireId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new UnauthenticatedException();
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new UnauthenticatedException();
        }
    }
}
