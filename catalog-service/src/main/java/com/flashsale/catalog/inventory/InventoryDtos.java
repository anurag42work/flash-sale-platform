package com.flashsale.catalog.inventory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record QuantityRequest(@NotNull @Min(1) @Max(1000) Integer quantity) {
    }

    /** unitPrice is read in the same transaction as the stock, so the order can snapshot it. */
    public record ReservationResponse(long productId, int quantity, BigDecimal unitPrice, int remaining) {
    }

    public record StockResponse(long productId, int available) {
    }
}
