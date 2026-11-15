package com.flashsale.catalog.inventory;

import com.flashsale.catalog.inventory.InventoryDtos.ReservationResponse;
import com.flashsale.catalog.inventory.InventoryDtos.StockResponse;
import com.flashsale.catalog.product.Product;
import com.flashsale.catalog.product.ProductNotFoundException;
import com.flashsale.catalog.product.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock reservation.
 *
 * <p>WARNING: NAIVE ON PURPOSE (incident 001, steps 1.5/1.6). Each method reads the row, checks and changes the
 * number in Java, then writes the new absolute value back, with no lock. Two concurrent requests can both read
 * available = 1, both pass the check and both write 0: two units sold, one taken from stock (lost update,
 * overselling). Do not copy this pattern; the fix is an atomic conditional UPDATE.
 */
@Service
public class InventoryService {

    private final InventoryRepository inventory;
    private final ProductRepository products;
    private final Clock clock;

    public InventoryService(InventoryRepository inventory, ProductRepository products, Clock clock) {
        this.inventory = inventory;
        this.products = products;
        this.clock = clock;
    }

    @Transactional
    public ReservationResponse reserve(long productId, int quantity) {
        Product product = products.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        Inventory stock = load(productId);

        // read ... check ... write: the race window is between these lines.
        int available = stock.getAvailable();
        if (available < quantity) {
            throw new OutOfStockException(productId);
        }
        stock.setAvailable(available - quantity, now());

        return new ReservationResponse(productId, quantity, product.getPrice(), available - quantity);
    }

    @Transactional
    public StockResponse release(long productId, int quantity) {
        Inventory stock = load(productId);
        stock.setAvailable(stock.getAvailable() + quantity, now()); // same lost-update problem as reserve
        return new StockResponse(productId, stock.getAvailable());
    }

    private Inventory load(long productId) {
        return inventory.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    }

    private Instant now() {
        return clock.instant();
    }
}
