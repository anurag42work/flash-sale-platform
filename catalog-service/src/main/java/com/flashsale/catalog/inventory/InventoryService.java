package com.flashsale.catalog.inventory;

import com.flashsale.catalog.inventory.InventoryDtos.ReservationResponse;
import com.flashsale.catalog.inventory.InventoryDtos.StockResponse;
import com.flashsale.catalog.product.Product;
import com.flashsale.catalog.product.ProductNotFoundException;
import com.flashsale.catalog.product.ProductRepository;
import java.sql.Timestamp;
import java.time.Clock;
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
        // Relative increment: concurrent releases each add their quantity, none is lost.
        Integer available = jdbc.sql("""
                        UPDATE inventory
                           SET available = available + :qty, updated_at = :now
                         WHERE product_id = :id
                        RETURNING available""")
                .param("qty", quantity)
                .param("now", now())
                .param("id", productId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new ProductNotFoundException(productId));
        return new StockResponse(productId, available);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
