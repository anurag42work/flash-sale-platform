package com.flashsale.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Products and inventory over HTTP against a real Postgres. Reserve/release are called one after another here;
 * concurrent calls (and the overselling bug) are step 1.5.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogFlowIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationContext context;

    @Test
    void adminCreatesProductAndAnyoneCanReadItWithStock() {
        long id = createProduct("19.99", 50);

        Map<String, Object> product = rest.getForObject("/api/products/" + id, Map.class);

        assertThat(product).containsEntry("price", 19.99).containsEntry("available", 50);
    }

    @Test
    void duplicateSkuReturns409() {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(post("/api/products", product(sku, "10.00", 1), true).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> second = post("/api/products", product(sku, "10.00", 1), true);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listIsPagedAndSorted() {
        createProduct("1000.01", 1);
        createProduct("1000.02", 1);
        createProduct("1000.03", 1);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/products?size=2&sort=price,desc", HttpMethod.GET, null, JSON);

        Map<String, Object> page = response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("price")).isEqualTo(1000.03);
        assertThat(content.get(1).get("price")).isEqualTo(1000.02);
        assertThat(page).containsEntry("page", 0).containsEntry("size", 2);
        assertThat((Integer) page.get("totalElements")).isGreaterThanOrEqualTo(3);
    }

    @Test
    void deactivatedProductIsHidden() {
        long id = createProduct("5.00", 1);
        Map<String, Object> update = Map.of("name", "Old", "price", 5.00, "active", false);

        ResponseEntity<Map<String, Object>> put = rest.exchange("/api/products/" + id, HttpMethod.PUT,
                new HttpEntity<>(update, adminHeaders()), JSON);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/api/products/" + id, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reserveTakesStockAndReturnsUnitPrice() {
        long id = createProduct("12.50", 10);

        ResponseEntity<Map<String, Object>> reserved = reserve(id, 3);

        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reserved.getBody()).containsEntry("unitPrice", 12.50).containsEntry("remaining", 7);
        assertThat(available(id)).isEqualTo(7);
    }

    @Test
    void reservingMoreThanAvailableReturns409AndKeepsStock() {
        long id = createProduct("12.50", 2);

        ResponseEntity<Map<String, Object>> reserved = reserve(id, 3);

        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reserved.getBody()).containsEntry("code", "OUT_OF_STOCK");
        assertThat(available(id)).isEqualTo(2);
    }

    @Test
    void releaseGivesStockBack() {
        long id = createProduct("12.50", 10);
        reserve(id, 4);

        ResponseEntity<Map<String, Object>> released = post("/internal/inventory/" + id + "/release",
                Map.of("quantity", 4), false);

        assertThat(released.getBody()).containsEntry("available", 10);
    }

    @Test
    void reservingUnknownProductReturns404() {
        assertThat(reserve(999_999_999L, 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void seederIsNotActiveWithoutTheDevProfile() {
        assertThat(context.containsBean("devDataSeeder")).isFalse();
    }

    // ---- helpers ----

    private long createProduct(String price, int stock) {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map<String, Object>> response = post("/api/products", product(sku, price, stock), true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) response.getBody().get("id")).longValue();
    }

    private static Map<String, Object> product(String sku, String price, int stock) {
        return Map.of("sku", sku, "name", "Product " + sku, "price", Double.valueOf(price), "initialStock", stock);
    }

    private ResponseEntity<Map<String, Object>> reserve(long id, int quantity) {
        return post("/internal/inventory/" + id + "/reserve", Map.of("quantity", quantity), false);
    }

    private int available(long id) {
        return (Integer) rest.getForObject("/api/products/" + id, Map.class).get("available");
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, boolean asAdmin) {
        HttpHeaders headers = asAdmin ? adminHeaders() : new HttpHeaders();
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JSON);
    }

    private static HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", UUID.randomUUID().toString());
        headers.set("X-User-Roles", "ADMIN");
        return headers;
    }
}
