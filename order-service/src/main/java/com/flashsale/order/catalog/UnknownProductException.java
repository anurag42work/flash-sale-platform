package com.flashsale.order.catalog;

public class UnknownProductException extends RuntimeException {

    private final long productId;

    public UnknownProductException(long productId) {
        super("Product " + productId + " does not exist");
        this.productId = productId;
    }

    public long getProductId() {
        return productId;
    }
}
