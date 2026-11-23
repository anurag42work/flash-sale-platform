package com.flashsale.order.order;

import com.flashsale.common.web.Headers;
import com.flashsale.common.web.PageResponse;
import com.flashsale.order.order.OrderDtos.CreateOrderRequest;
import com.flashsale.order.order.OrderDtos.OrderResponse;
import com.flashsale.order.web.RequestUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every endpoint works on the caller's own orders; the user id comes from the gateway's X-User-Id header. */
@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(
            @RequestHeader(name = Headers.USER_ID, required = false) String userId,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orders.create(RequestUser.requireId(userId), request);
        return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    OrderResponse get(
            @RequestHeader(name = Headers.USER_ID, required = false) String userId,
            @PathVariable UUID id) {
        return orders.get(RequestUser.requireId(userId), id);
    }

    @GetMapping
    PageResponse<OrderResponse> list(
            @RequestHeader(name = Headers.USER_ID, required = false) String userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.list(RequestUser.requireId(userId), page, size);
    }
}
