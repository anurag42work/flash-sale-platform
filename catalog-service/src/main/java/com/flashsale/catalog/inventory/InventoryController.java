package com.flashsale.catalog.inventory;

import com.flashsale.catalog.inventory.InventoryDtos.QuantityRequest;
import com.flashsale.catalog.inventory.InventoryDtos.ReservationResponse;
import com.flashsale.catalog.inventory.InventoryDtos.StockResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API for other services (order-service). The gateway has no route for /internal/**, so clients
 * cannot call it; only services on the internal network can.
 */
@RestController
@RequestMapping("/internal/inventory/{productId}")
class InventoryController {

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PostMapping("/reserve")
    ReservationResponse reserve(@PathVariable long productId, @Valid @RequestBody QuantityRequest request) {
        return inventory.reserve(productId, request.quantity());
    }

    @PostMapping("/release")
    StockResponse release(@PathVariable long productId, @Valid @RequestBody QuantityRequest request) {
        return inventory.release(productId, request.quantity());
    }
}
