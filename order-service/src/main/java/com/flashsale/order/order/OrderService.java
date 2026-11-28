package com.flashsale.order.order;

import com.flashsale.common.web.PageResponse;
import com.flashsale.order.catalog.CatalogClient;
import com.flashsale.order.catalog.CatalogClient.Reservation;
import com.flashsale.order.order.OrderDtos.CreateOrderRequest;
import com.flashsale.order.order.OrderDtos.OrderLine;
import com.flashsale.order.order.OrderDtos.OrderResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final CatalogClient catalog;
    private final OrderEventPublisher events;
    private final Clock clock;

    public OrderService(OrderRepository orders, CatalogClient catalog, OrderEventPublisher events, Clock clock) {
        this.orders = orders;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Order creation: reserve every item at catalog, save the order as PENDING, then publish OrderCreated.
     * The caller gets 202 Accepted: payment happens later, driven by the event.
     *
     * <p>Not @Transactional on purpose: no DB connection is held while waiting for catalog over HTTP.
     * The final save runs in its own short transaction (the repository's).
     *
     * <p>Known gap (dual write, Day 2): if this service crashes or the save fails after the reservations,
     * the reserved stock is never released. The outbox + saga in steps 2.1–2.3 address this.
     */
    public OrderResponse create(UUID userId, CreateOrderRequest request) {
        List<Reservation> reserved = new ArrayList<>();
        try {
            for (OrderLine line : request.items()) {
                reserved.add(catalog.reserve(line.productId(), line.quantity()));
            }
        } catch (RuntimeException e) {
            releaseAll(reserved); // compensate: give back what this order already took
            throw e;
        }

        Order order = Order.pending(userId, clock.instant());
        reserved.forEach(r -> order.addItem(r.productId(), r.quantity(), r.unitPrice()));
        Order saved = orders.save(order); // commits here (the repository's own transaction)
        events.publishCreated(saved);   // second write, to Kafka, after the commit: incident 003
        return OrderResponse.from(saved);
    }

    // readOnly transaction: the items are loaded lazily while mapping, which needs an open session.
    @Transactional(readOnly = true)
    public OrderResponse get(UUID userId, UUID orderId) {
        return orders.findByIdAndUserId(orderId, userId)
                .map(OrderResponse::from)
                .orElseThrow(OrderNotFoundException::new);
    }

    /** Note: items load lazily, one query per order on the page (N+1, incident 006 on Day 4). */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(UUID userId, int page, int size) {
        Page<OrderResponse> result = orders.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(OrderResponse::from);
        return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    private void releaseAll(List<Reservation> reserved) {
        for (Reservation r : reserved) {
            try {
                catalog.release(r.productId(), r.quantity());
            } catch (RuntimeException e) {
                // Best effort. Lost stock here is the same dual-write gap as above.
                log.error("Could not release {} units of product {}", r.quantity(), r.productId(), e);
            }
        }
    }
}
