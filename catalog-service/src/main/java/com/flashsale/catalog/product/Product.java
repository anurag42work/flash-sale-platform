package com.flashsale.catalog.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {

    // GENERATED ALWAYS AS IDENTITY in Postgres: the database assigns the id on insert.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Product() {
        // for JPA
    }

    public Product(String sku, String name, String description, BigDecimal price, Instant now) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String description, BigDecimal price, boolean active, Instant now) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.active = active;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }
}
