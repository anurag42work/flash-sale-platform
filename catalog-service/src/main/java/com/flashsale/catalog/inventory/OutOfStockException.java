package com.flashsale.catalog.inventory;

public class OutOfStockException extends RuntimeException {

    private final long productId;

    public OutOfStockException(long productId) {
        super("Not enough stock for product " + productId);
        this.productId = productId;
    }

    public long getProductId() {
        return productId;
    }
}
