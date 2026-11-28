package com.flashsale.catalog.inventory;

import com.flashsale.catalog.inventory.InventoryDtos.ReservationResponse;
import com.flashsale.catalog.inventory.InventoryDtos.StockResponse;
import com.flashsale.catalog.product.Product;
import com.flashsale.catalog.product.ProductNotFoundException;
import com.flashsale.catalog.product.ProductRepository;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderFailed;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock reservation (incident 001 fix).
 *
 * <p>Check and change happen in ONE SQL statement, so the database does them atomically:
 * {@code UPDATE ... SET available = available - :qty WHERE ... AND available >= :qty}. Postgres locks the row
 * for the UPDATE and evaluates the WHERE against the latest committed value, so a concurrent buyer either sees
 * enough stock and decrements it, or matches 0 rows and gets 409. Never read the stock, change it in Java and
 * write it back: that was the lost-update bug (see docs/incidents/001-overselling.md).
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final JdbcClient jdbc;
    private final ProductRepository products;
    private final Clock clock;

    public InventoryService(JdbcClient jdbc, ProductRepository products, Clock clock) {
        this.jdbc = jdbc;
        this.products = products;
        this.clock = clock;
    }

    @Transactional
    public ReservationResponse reserve(long productId, int quantity) {
        // Read first only for the 404 and the price snapshot; the stock decision is made by the UPDATE below.
        Product product = products.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        Integer remaining = jdbc.sql("""
                        UPDATE inventory
                           SET available = available - :qty, updated_at = :now
                         WHERE product_id = :id AND available >= :qty
                        RETURNING available""")
                .param("qty", quantity)
                .param("now", now())
                .param("id", productId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new OutOfStockException(productId)); // 0 rows: not enough stock

        return new ReservationResponse(productId, quantity, product.getPrice(), remaining);
    }

    @Transactional
    public StockResponse release(long productId, int quantity) {
        Integer available = addStock(productId, quantity)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        return new StockResponse(productId, available);
    }

    /**
     * Saga compensation (step 2.3): give back the stock of an order that failed, exactly once per OrderFailed event.
     * One transaction: claim the eventId in processed_events, then add each item back. A redelivered event finds its
     * id already claimed and changes nothing (a second release would create stock out of thin air).
     *
     * @return false if the event was a duplicate
     */
    @Transactional
    public boolean releaseForFailedOrder(EventEnvelope<OrderFailed> event) {
        int claimed = jdbc.sql("""
                        INSERT INTO processed_events (event_id, event_type) VALUES (:id, :type)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("id", event.eventId())
                .param("type", event.type())
                .update();
        if (claimed == 0) {
            log.info("Duplicate event {} for order {} skipped", event.eventId(), event.data().orderId());
            return false;
        }
        for (OrderFailed.Item item : event.data().items()) {
            if (addStock(item.productId(), item.quantity()).isEmpty()) {
                // Products are never deleted, so this means bad data; retrying would not help.
                log.error("Order {}: product {} not found, {} units not released", event.data().orderId(),
                        item.productId(), item.quantity());
            }
        }
        return true;
    }

    // Relative increment: concurrent releases each add their quantity, none is lost.
    private Optional<Integer> addStock(long productId, int quantity) {
        return jdbc.sql("""
                        UPDATE inventory
                           SET available = available + :qty, updated_at = :now
                         WHERE product_id = :id
                        RETURNING available""")
                .param("qty", quantity)
                .param("now", now())
                .param("id", productId)
                .query(Integer.class)
                .optional();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
