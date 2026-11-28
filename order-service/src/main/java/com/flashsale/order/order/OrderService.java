package com.flashsale.order.order;

import com.flashsale.common.event.OrderCreated;
import com.flashsale.common.web.PageResponse;
import com.flashsale.order.catalog.CatalogClient;
import com.flashsale.order.catalog.CatalogClient.Reservation;
import com.flashsale.order.order.OrderDtos.CreateOrderRequest;
import com.flashsale.order.order.OrderDtos.OrderLine;
import com.flashsale.order.order.OrderDtos.OrderResponse;
import com.flashsale.order.outbox.OutboxWriter;
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
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final CatalogClient catalog;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderService(OrderRepository orders, CatalogClient catalog, OutboxWriter outbox, TransactionTemplate tx,
            Clock clock) {
        this.orders = orders;
        this.catalog = catalog;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Order creation: reserve every item at catalog, then in ONE transaction save the order as PENDING and an
     * outbox row for its OrderCreated event (incident 003). OutboxRelay publishes the event afterwards.
     * The caller gets 202 Accepted: payment happens later, driven by the event.
     *
     * <p>The catalog calls stay outside the transaction on purpose: no DB connection is held while waiting on HTTP.
     * If the transaction fails, the reservations are released (best effort).
     *
     * <p>Remaining gap: if this service crashes between reserving and committing, the stock stays reserved.
     * The saga (2.3) and the reconciliation job (2.6) cover that.
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
        Order saved;
        try {
            saved = tx.execute(status -> {
                Order o = orders.save(order); // the UUID id is assigned here
                outbox.add(OrderCreated.TOPIC, o.getId(), OrderCreated.TYPE, OrderCreated.VERSION, toEvent(o));
                return o;
            }); // order + outbox row commit together, or neither does
        } catch (RuntimeException e) {
            releaseAll(reserved);
            throw e;
        }
        return OrderResponse.from(saved);
    }

    private static OrderCreated toEvent(Order order) {
        return new OrderCreated(order.getId(), order.getUserId(), order.getTotalAmount(),
                order.getItems().stream()
                        .map(i -> new OrderCreated.Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                        .toList());
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
