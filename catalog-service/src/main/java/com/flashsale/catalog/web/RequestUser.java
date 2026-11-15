package com.flashsale.catalog.web;

import java.util.Arrays;

/**
 * Reads the identity the gateway put in X-User-Id / X-User-Roles. The gateway removes any client-sent
 * values and sets them from a validated JWT, so the service can trust them (internal network only).
 */
public final class RequestUser {

    private RequestUser() {
    }

    public static void requireAdmin(String userId, String roles) {
        if (userId == null || userId.isBlank()) {
            throw new UnauthenticatedException();
        }
        boolean admin = roles != null && Arrays.stream(roles.split(",")).map(String::trim).anyMatch("ADMIN"::equals);
        if (!admin) {
            throw new ForbiddenException();
        }
    }
}
