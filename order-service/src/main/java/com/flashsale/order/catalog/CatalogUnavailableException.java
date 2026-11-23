package com.flashsale.order.catalog;

public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(Throwable cause) {
        super("Catalog service is unavailable", cause);
    }
}
