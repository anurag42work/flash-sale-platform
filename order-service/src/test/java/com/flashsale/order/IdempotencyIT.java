package com.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Incident 002: duplicate orders. A client sends POST /api/orders, the response is lost (timeout, mobile network
 * drop), and the client retries the same request with the same Idempotency-Key. The retry must not create a
 * second order or reserve stock a second time.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    private static final FakeCatalog CATALOG = new FakeCatalog();

    @DynamicPropertySource
    static void catalog(DynamicPropertyRegistry registry) {
        registry.add("catalog.base-url", CATALOG::baseUrl);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetCatalog() {
        CATALOG.reset();
    }

    @Test
    void retryWithSameKeyCreatesOneOrderAndReservesStockOnce() {
        CATALOG.product(1, "10.00", 10);
        UUID user = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = order(1, 2);

        ResponseEntity<Map<String, Object>> first = post(user, key, body);
        ResponseEntity<Map<String, Object>> retry = post(user, key, body); // client retries after a "timeout"

        long orders = ordersOf(user);
        int stock = CATALOG.stock(1);
        System.out.printf("%n=== INCIDENT 002: 2 POSTs, same Idempotency-Key -> statuses %s / %s, orders=%d,"
                + " stock=%d (start 10, qty 2), order ids %s / %s ===%n%n",
                first.getStatusCode().value(), retry.getStatusCode().value(), orders, stock,
                first.getBody().get("id"), retry.getBody().get("id"));

        assertThat(orders).as("orders created").isEqualTo(1);
        assertThat(stock).as("stock left (reserved once)").isEqualTo(8);
        assertThat(retry.getBody().get("id")).as("retry returns the same order").isEqualTo(first.getBody().get("id"));
    }

    // ---- helpers ----

    static Map<String, Object> order(long productId, int quantity) {
        return Map.of("items", List.of(Map.of("productId", productId, "quantity", quantity)));
    }

    ResponseEntity<Map<String, Object>> post(UUID user, String key, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange("/api/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JSON);
    }

    long ordersOf(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Long.class, user);
    }
}
