package com.flashsale.catalog.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Stock of one product (1:1 with products, same id). */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    private Long productId;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Inventory() {
        // for JPA
    }

    public Inventory(Long productId, int available, Instant now) {
        this.productId = productId;
        this.available = available;
        this.updatedAt = now;
    }

    public int getAvailable() {
        return available;
    }

    void setAvailable(int available, Instant now) {
        this.available = available;
        this.updatedAt = now;
    }
}
