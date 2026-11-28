package com.flashsale.order.order;

import com.flashsale.common.web.Headers;
import com.flashsale.common.web.PageResponse;
import com.flashsale.order.idempotency.IdempotencyService;
import com.flashsale.order.idempotency.IdempotencyService.Result;
import com.flashsale.order.order.OrderDtos.CreateOrderRequest;
import com.flashsale.order.order.OrderDtos.OrderResponse;
import com.flashsale.order.web.RequestUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

    /** Set on a replayed response so clients (and tests) can tell it was not processed again. */
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final OrderService orders;
    private final IdempotencyService idempotency;

    OrderController(OrderService orders, IdempotencyService idempotency) {
        this.orders = orders;
        this.idempotency = idempotency;
    }

    /**
     * Idempotent create (incident 002): a retry with the same Idempotency-Key returns the first response
     * instead of creating a second order. 202 Accepted: the order is PENDING until payment (async) decides.
     */
    @PostMapping
    ResponseEntity<OrderResponse> create(
            @RequestHeader(name = Headers.USER_ID, required = false) String userId,
            @RequestHeader(name = Headers.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        UUID user = RequestUser.requireId(userId);
        String key = IdempotencyService.requireKey(idempotencyKey);

        Result<OrderResponse> result = idempotency.execute(user, key, request, OrderResponse.class,
                HttpStatus.ACCEPTED.value(), () -> orders.create(user, request));

        OrderResponse created = result.body();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status())
                .location(URI.create("/api/orders/" + created.id()));
        if (result.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(created);
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
