package com.flashsale.order.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(@NotEmpty @Size(max = 50) List<@NotNull @Valid OrderLine> items) {

        /** One line per product (the DB has UNIQUE (order_id, product_id)). Reported as "uniqueProducts". */
        @JsonIgnore
        @AssertTrue(message = "each product may appear only once")
        public boolean isUniqueProducts() {
            if (items == null) {
                return true;
            }
            List<Long> ids = items.stream().filter(Objects::nonNull).map(OrderLine::productId).toList();
            return ids.size() == ids.stream().distinct().count();
        }
    }

    public record OrderLine(@NotNull @Min(1) Long productId, @NotNull @Min(1) @Max(100) Integer quantity) {
    }

    public record OrderItemResponse(long productId, int quantity, BigDecimal unitPrice) {
    }

    public record OrderResponse(UUID id, OrderStatus status, BigDecimal totalAmount, Instant createdAt,
            List<OrderItemResponse> items) {

        static OrderResponse from(Order order) {
            List<OrderItemResponse> items = order.getItems().stream()
                    .map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                    .toList();
            return new OrderResponse(order.getId(), order.getStatus(), order.getTotalAmount(), order.getCreatedAt(),
                    items);
        }
    }
}
