package com.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashsale.order.idempotency.IdempotencyCleanup;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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

    @Autowired
    IdempotencyCleanup cleanup;

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

    @Test
    void replayIsMarkedAndHasTheSameStatusLocationAndBody() {
        CATALOG.product(1, "10.00", 10);
        UUID user = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map<String, Object>> first = post(user, key, order(1, 1));
        ResponseEntity<Map<String, Object>> retry = post(user, key, order(1, 1));

        assertThat(first.getHeaders().containsKey("Idempotent-Replayed")).isFalse();
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(retry.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void twentyConcurrentRequestsWithSameKeyCreateExactlyOneOrder() throws Exception {
        CATALOG.product(1, "10.00", 100);
        UUID user = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        int calls = 20;

        List<ResponseEntity<Map<String, Object>>> responses = concurrently(calls, () -> post(user, key, order(1, 1)));

        List<Integer> statuses = responses.stream().map(r -> r.getStatusCode().value()).toList();
        Set<Object> ids = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                .map(r -> r.getBody().get("id"))
                .collect(Collectors.toSet());
        System.out.printf("%n=== INCIDENT 002: %d concurrent POSTs, same key -> 201=%d, 409=%d, orders=%d,"
                + " stock=%d (start 100, qty 1) ===%n%n", calls, Collections.frequency(statuses, 201),
                Collections.frequency(statuses, 409), ordersOf(user), CATALOG.stock(1));

        assertThat(ordersOf(user)).as("orders created").isEqualTo(1);
        assertThat(CATALOG.stock(1)).as("stock reserved once").isEqualTo(99);
        assertThat(statuses).as("first wins (201), others replay (201) or wait (409)").containsOnly(201, 409);
        assertThat(ids).as("every 201 carries the same order").hasSize(1);
    }

    @Test
    void secondRequestWhileFirstIsRunningGets409() throws Exception {
        CATALOG.product(1, "10.00", 10);
        CATALOG.delay(1_000); // keeps the first request IN_PROGRESS for a second
        UUID user = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        CompletableFuture<ResponseEntity<Map<String, Object>>> first =
                CompletableFuture.supplyAsync(() -> post(user, key, order(1, 1)));
        awaitKeyRow(user, key);
        ResponseEntity<Map<String, Object>> second = post(user, key, order(1, 1));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_IN_PROGRESS");
        assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ordersOf(user)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyReturns422() {
        CATALOG.product(1, "10.00", 10);
        UUID user = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        post(user, key, order(1, 1));

        ResponseEntity<Map<String, Object>> other = post(user, key, order(1, 5));

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(other.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(ordersOf(user)).isEqualTo(1);
        assertThat(CATALOG.stock(1)).isEqualTo(9);
    }

    @Test
    void failedRequestFreesTheKeySoTheRetryCanSucceed() {
        CATALOG.product(1, "10.00", 1);
        UUID user = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map<String, Object>> failed = post(user, key, order(1, 2));
        CATALOG.product(1, "10.00", 5); // restocked
        ResponseEntity<Map<String, Object>> retry = post(user, key, order(1, 2));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failed.getBody()).containsEntry("code", "OUT_OF_STOCK");
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().containsKey("Idempotent-Replayed")).isFalse();
        assertThat(ordersOf(user)).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerUser() {
        CATALOG.product(1, "10.00", 10);
        String key = "shared-key-" + UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        Object aliceOrder = post(alice, key, order(1, 1)).getBody().get("id");
        ResponseEntity<Map<String, Object>> bobResponse = post(bob, key, order(1, 1));

        assertThat(bobResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bobResponse.getBody().get("id")).as("Bob gets his own order, not Alice's").isNotEqualTo(aliceOrder);
        assertThat(ordersOf(alice)).isEqualTo(1);
        assertThat(ordersOf(bob)).isEqualTo(1);
    }

    @Test
    void missingKeyReturns400AndCreatesNothing() {
        CATALOG.product(1, "10.00", 10);
        UUID user = UUID.randomUUID();

        assertThat(post(user, null, order(1, 1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ordersOf(user)).isZero();
        assertThat(CATALOG.stock(1)).isEqualTo(10);
    }

    @Test
    void cleanupDeletesOnlyExpiredKeys() {
        UUID user = UUID.randomUUID();
        insertKey(user, "old", Instant.now().minus(Duration.ofHours(25)));
        insertKey(user, "fresh", Instant.now().minus(Duration.ofHours(1)));

        cleanup.deleteExpired();

        assertThat(jdbc.queryForList("SELECT idem_key FROM idempotency_keys WHERE user_id = ?", String.class, user))
                .containsExactly("fresh");
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

    /** Same start-together pattern as incident 001: all threads wait on one latch, then fire at once. */
    private static <T> List<T> concurrently(int calls, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        CountDownLatch ready = new CountDownLatch(calls);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitKeyRow(UUID user, String key) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Long rows = jdbc.queryForObject(
                    "SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?", Long.class, user, key);
            if (rows == 1) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("first request never claimed the key");
    }

    private void insertKey(UUID user, String key, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_keys (user_id, idem_key, request_hash, status, created_at)"
                + " VALUES (?, ?, repeat('0', 64), 'IN_PROGRESS', ?)", user, key, Timestamp.from(createdAt));
    }
}
