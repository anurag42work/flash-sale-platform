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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Order flow over HTTP: real Postgres (Testcontainers) + a fake catalog-service. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderFlowIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    private static final FakeCatalog CATALOG = new FakeCatalog();

    @DynamicPropertySource
    static void catalog(DynamicPropertyRegistry registry) {
        registry.add("catalog.base-url", CATALOG::baseUrl);
        registry.add("catalog.read-timeout", () -> "500ms"); // keeps the timeout test fast
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
    void createsPendingOrderWithPriceSnapshotAndTotal() {
        CATALOG.product(1, "10.00", 5);
        CATALOG.product(2, "2.50", 5);
        UUID user = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = createOrder(user, line(1, 2), line(2, 4));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> order = response.getBody();
        assertThat(order).containsEntry("status", "PENDING").containsEntry("totalAmount", 30.00);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/orders/" + order.get("id"));
        assertThat(CATALOG.stock(1)).isEqualTo(3);
        assertThat(CATALOG.stock(2)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT user_id, status, total_amount FROM orders WHERE id = ?::uuid",
                order.get("id"));
        assertThat(row.get("user_id")).isEqualTo(user);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id = ?::uuid AND product_id = 2",
                String.class, order.get("id"))).isEqualTo("2.50");
    }

    @Test
    void outOfStockReleasesItemsAlreadyReservedAndSavesNothing() {
        CATALOG.product(1, "10.00", 5);
        CATALOG.product(2, "2.50", 1);
        UUID user = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = createOrder(user, line(1, 2), line(2, 3));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "OUT_OF_STOCK").containsEntry("productId", 2);
        assertThat(CATALOG.stock(1)).as("item 1 was reserved, then released").isEqualTo(5);
        assertThat(ordersOf(user)).isZero();
    }

    @Test
    void unknownProductReturns422() {
        ResponseEntity<Map<String, Object>> response = createOrder(UUID.randomUUID(), line(404, 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void slowCatalogReturns503AfterTheReadTimeout() {
        CATALOG.product(1, "10.00", 5);
        CATALOG.delay(1_500); // longer than the 500 ms read timeout
        UUID user = UUID.randomUUID();

        long start = System.nanoTime();
        ResponseEntity<Map<String, Object>> response = createOrder(user, line(1, 1));
        long tookMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(tookMillis).as("gave up at the timeout instead of waiting").isLessThan(1_500);
        assertThat(ordersOf(user)).isZero();
    }

    @Test
    void usersSeeOnlyTheirOwnOrders() {
        CATALOG.product(1, "10.00", 10);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String aliceOrder = (String) createOrder(alice, line(1, 1)).getBody().get("id");

        assertThat(get("/api/orders/" + aliceOrder, alice).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Bob gets 404, not 403: he cannot even learn that the order exists.
        assertThat(get("/api/orders/" + aliceOrder, bob).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listReturnsOwnOrdersNewestFirstPaged() {
        CATALOG.product(1, "10.00", 10);
        UUID user = UUID.randomUUID();
        createOrder(user, line(1, 1));
        createOrder(user, line(1, 2));
        String newest = (String) createOrder(user, line(1, 3)).getBody().get("id");
        createOrder(UUID.randomUUID(), line(1, 1)); // someone else's

        Map<String, Object> page = get("/api/orders?size=2", user).getBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("id")).isEqualTo(newest);
        assertThat(page).containsEntry("totalElements", 3).containsEntry("totalPages", 2);
    }

    // ---- helpers ----

    private static Map<String, Object> line(long productId, int quantity) {
        return Map.of("productId", productId, "quantity", quantity);
    }

    @SafeVarargs
    private ResponseEntity<Map<String, Object>> createOrder(UUID user, Map<String, Object>... lines) {
        return rest.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", List.of(lines)), userHeaders(user)), JSON);
    }

    private ResponseEntity<Map<String, Object>> get(String path, UUID user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(userHeaders(user)), JSON);
    }

    private long ordersOf(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Long.class, user);
    }

    private static HttpHeaders userHeaders(UUID user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        return headers;
    }
}
