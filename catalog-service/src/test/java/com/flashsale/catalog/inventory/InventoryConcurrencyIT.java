package com.flashsale.catalog.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flashsale.catalog.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Incident 001: overselling. A flash sale is many buyers reserving the same product at the same moment.
 * 50 units, 200 concurrent reserve calls of quantity 1 through the real HTTP endpoint and a real Postgres.
 * Correct result: exactly 50 calls succeed (200), 150 get 409 OUT_OF_STOCK, and the stock ends at 0.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryConcurrencyIT {

    private static final int STOCK = 50;
    private static final int BUYERS = 200;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentReservesNeverSellMoreThanTheStock() throws Exception {
        long productId = createProduct(STOCK);

        List<Integer> statuses = callConcurrently(productId, "reserve", BUYERS);

        long ok = statuses.stream().filter(s -> s == 200).count();
        long outOfStock = statuses.stream().filter(s -> s == 409).count();
        long other = statuses.size() - ok - outOfStock;
        int finalStock = available(productId);
        System.out.printf("%n=== INCIDENT 001: stock=%d, buyers=%d -> successes=%d, 409=%d, other=%d, final stock=%d,"
                + " units sold but not taken from stock=%d ===%n%n",
                STOCK, BUYERS, ok, outOfStock, other, finalStock, ok - (STOCK - finalStock));

        // Soft assertions: report every broken number at once, not just the first.
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(ok).as("successful reservations").isEqualTo(STOCK);
        soft.assertThat(outOfStock).as("409 OUT_OF_STOCK responses").isEqualTo(BUYERS - STOCK);
        soft.assertThat(other).as("responses that were neither 200 nor 409").isZero();
        soft.assertThat(finalStock).as("final stock").isZero();
        soft.assertAll();
    }

    /** Release has the same read-modify-write shape: concurrent releases must all be counted. */
    @Test
    void concurrentReleasesAreAllCounted() throws Exception {
        long productId = createProduct(0);

        List<Integer> statuses = callConcurrently(productId, "release", STOCK);

        int finalStock = available(productId);
        System.out.printf("%n=== INCIDENT 001 (release): %d concurrent releases -> final stock=%d (expected %d) ===%n%n",
                STOCK, finalStock, STOCK);
        assertThat(statuses).containsOnly(200);
        assertThat(finalStock).isEqualTo(STOCK);
    }

    /** Safety net in the database: even a buggy code path can't store negative stock. */
    @Test
    void databaseRejectsNegativeStock() {
        long productId = createProduct(1);

        assertThatThrownBy(() -> jdbc.update("UPDATE inventory SET available = -1 WHERE product_id = ?", productId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inventory_available");
    }

    /**
     * Starts all calls at the same moment: each thread waits at {@code ready} until all are created, then all
     * block on {@code start}, which the test opens once. Without the latch, early threads would finish before
     * late ones start and the race would rarely show.
     */
    private List<Integer> callConcurrently(long productId, String action, int calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        CountDownLatch ready = new CountDownLatch(calls);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return post("/internal/inventory/" + productId + "/" + action, Map.of("quantity", 1), false)
                            .getStatusCode().value();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers ----

    private long createProduct(int stock) {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = Map.of("sku", sku, "name", "Flash " + sku, "price", 9.99, "initialStock", stock);
        ResponseEntity<Map> response = post("/api/products", body, true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) response.getBody().get("id")).longValue();
    }

    private int available(long productId) {
        return (Integer) rest.getForObject("/api/products/" + productId, Map.class).get("available");
    }

    private ResponseEntity<Map> post(String path, Object body, boolean asAdmin) {
        HttpHeaders headers = new HttpHeaders();
        if (asAdmin) {
            headers.set("X-User-Id", UUID.randomUUID().toString());
            headers.set("X-User-Roles", "ADMIN");
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
