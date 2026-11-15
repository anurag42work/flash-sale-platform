package com.flashsale.catalog.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request and response bodies of the product API. Entities are never returned directly. */
public final class ProductDtos {

    private ProductDtos() {
    }

    public record CreateProductRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9-]+", message = "letters, digits and '-' only")
            String sku,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            // numeric(12,2): at most 10 digits before the point and 2 after, and price > 0 (DB CHECK).
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull @Min(0) @Max(1_000_000) Integer initialStock) {
    }

    public record UpdateProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull Boolean active) {
    }

    /** Used in lists and for create/update responses. */
    public record ProductResponse(Long id, String sku, String name, String description, BigDecimal price,
            boolean active) {

        static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(),
                    p.isActive());
        }
    }

    /** Single product page: also shows the current stock. */
    public record ProductDetailResponse(Long id, String sku, String name, String description, BigDecimal price,
            int available) {
    }
}
