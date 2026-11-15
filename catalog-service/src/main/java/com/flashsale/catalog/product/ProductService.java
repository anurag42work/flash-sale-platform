package com.flashsale.catalog.product;

import com.flashsale.catalog.inventory.Inventory;
import com.flashsale.catalog.inventory.InventoryRepository;
import com.flashsale.catalog.product.ProductDtos.CreateProductRequest;
import com.flashsale.catalog.product.ProductDtos.ProductDetailResponse;
import com.flashsale.catalog.product.ProductDtos.ProductResponse;
import com.flashsale.catalog.product.ProductDtos.UpdateProductRequest;
import com.flashsale.common.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final Clock clock;

    public ProductService(ProductRepository products, InventoryRepository inventory, Clock clock) {
        this.products = products;
        this.inventory = inventory;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(Pageable pageable) {
        Page<ProductResponse> page = products.findByActiveTrue(pageable).map(ProductResponse::from);
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse get(long id) {
        Product p = products.findByIdAndActiveTrue(id).orElseThrow(() -> new ProductNotFoundException(id));
        int available = inventory.findById(id).map(Inventory::getAvailable).orElse(0);
        return new ProductDetailResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(),
                available);
    }

    /** Product and its stock row are created together in one transaction. */
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        if (products.existsBySku(request.sku())) {
            throw new DuplicateSkuException(request.sku());
        }
        Instant now = clock.instant();
        try {
            Product product = products.saveAndFlush(
                    new Product(request.sku(), request.name(), request.description(), request.price(), now));
            inventory.save(new Inventory(product.getId(), request.initialStock(), now));
            return ProductResponse.from(product);
        } catch (DataIntegrityViolationException e) {
            // Two creates with the same SKU at the same time: the unique constraint stops the second.
            throw new DuplicateSkuException(request.sku());
        }
    }

    @Transactional
    public ProductResponse update(long id, UpdateProductRequest request) {
        Product product = products.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        product.update(request.name(), request.description(), request.price(), request.active(), clock.instant());
        return ProductResponse.from(product);
    }
}
