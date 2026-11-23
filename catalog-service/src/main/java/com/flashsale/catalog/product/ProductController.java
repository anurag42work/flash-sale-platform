package com.flashsale.catalog.product;

import com.flashsale.catalog.product.ProductDtos.CreateProductRequest;
import com.flashsale.catalog.product.ProductDtos.ProductDetailResponse;
import com.flashsale.catalog.product.ProductDtos.ProductResponse;
import com.flashsale.catalog.product.ProductDtos.UpdateProductRequest;
import com.flashsale.catalog.web.BadRequestException;
import com.flashsale.catalog.web.RequestUser;
import com.flashsale.common.web.Headers;
import com.flashsale.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
class ProductController {

    // Only these fields can be sorted on. Anything else would be a 500 (unknown property) or a slow sort.
    private static final Set<String> SORTABLE = Set.of("id", "name", "price");

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    PageResponse<ProductResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "id,asc") String sort) {
        return products.list(PageRequest.of(page, size, parseSort(sort)));
    }

    @GetMapping("/{id}")
    ProductDetailResponse get(@PathVariable long id) {
        return products.get(id);
    }

    @PostMapping
    ResponseEntity<ProductResponse> create(
            @RequestHeader(name = Headers.USER_ID, required = false) String userId,
            @RequestHeader(name = Headers.USER_ROLES, required = false) String roles,
            @Valid @RequestBody CreateProductRequest request) {
        RequestUser.requireAdmin(userId, roles);
        ProductResponse created = products.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    ProductResponse update(
            @RequestHeader(name = Headers.USER_ID, required = false) String userId,
            @RequestHeader(name = Headers.USER_ROLES, required = false) String roles,
            @PathVariable long id,
            @Valid @RequestBody UpdateProductRequest request) {
        RequestUser.requireAdmin(userId, roles);
        return products.update(id, request);
    }

    /** "price,desc" -> Sort.by(DESC, "price"). */
    private static Sort parseSort(String sort) {
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        String direction = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "asc";
        if (parts.length > 2 || !SORTABLE.contains(field) || !Set.of("asc", "desc").contains(direction)) {
            throw new BadRequestException("sort must be one of " + SORTABLE + " followed by ,asc or ,desc");
        }
        return Sort.by(Sort.Direction.fromString(direction), field);
    }
}
