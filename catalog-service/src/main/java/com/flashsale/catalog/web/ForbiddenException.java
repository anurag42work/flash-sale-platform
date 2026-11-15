package com.flashsale.catalog.web;

public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super("ADMIN role required");
    }
}
