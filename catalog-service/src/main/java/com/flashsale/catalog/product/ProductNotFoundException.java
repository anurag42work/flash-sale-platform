package com.flashsale.catalog.product;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(long id) {
        super("Product " + id + " not found");
    }
}
