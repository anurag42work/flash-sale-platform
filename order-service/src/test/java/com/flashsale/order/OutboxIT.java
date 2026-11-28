package com.flashsale.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.OrderCreated;
import com.flashsale.order.outbox.OutboxRelay;
import com.flashsale.order.outbox.OutboxWriter;
import com.github.dockerjava.api.DockerClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

/** Incident 003 fix: the transactional outbox and its SKIP LOCKED relay, against real Postgres and Kafka. */
@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listeners and outbox relay running and
// would react to the next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    private static final FakeCatalog CATALOG = new FakeCatalog();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalog.base-url", CATALOG::baseUrl);
        // Small batches, so the two-relay test needs many runs and the relays really compete for rows.
        registry.add("outbox.batch-size", () -> "5");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OutboxRelay relay;

    @MockitoSpyBean
    OutboxWriter outbox;

    KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        CATALOG.reset();
        jdbc.update("DELETE FROM outbox"); // each test counts only its own rows
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(OrderCreated.TOPIC));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void kafkaDownOrdersStillAcceptedAndEveryEventIsDeliveredOnceKafkaIsBack() throws Exception {
        CATALOG.product(1, "10.00", 100);
        DockerClient docker = DockerClientFactory.instance().client();
        String kafkaId = TestcontainersConfiguration.KAFKA.getContainerId();
        List<String> orderIds = new ArrayList<>();
        long downMillis;
        docker.pauseContainerCmd(kafkaId).exec();
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                ResponseEntity<Map<String, Object>> response = createOrder(UUID.randomUUID());
                assertThat(response.getStatusCode()).as("orders do not need Kafka any more").isEqualTo(HttpStatus.ACCEPTED);
                orderIds.add((String) response.getBody().get("id"));
            }
            long acceptMillis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("%n=== INCIDENT 003 fix: Kafka paused, 5 orders accepted in %d ms ===%n", acceptMillis);

            // The relay keeps trying the oldest row and records the failure.
            awaitTrue(Duration.ofSeconds(40), () -> jdbc.queryForObject(
                    "SELECT count(*) FROM outbox WHERE aggregate_id::text = ANY(?) AND attempts > 0 AND last_error IS NOT NULL",
                    Integer.class, (Object) orderIds.toArray(String[]::new)) > 0);
            assertThat(unsent(orderIds)).as("nothing published while Kafka is down").isEqualTo(5);
            downMillis = (System.nanoTime() - start) / 1_000_000;
        } finally {
            docker.unpauseContainerCmd(kafkaId).exec();
        }

        long upAt = System.nanoTime();
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        awaitTrue(Duration.ofSeconds(60), () -> {
            consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                if (orderIds.contains(r.key())) {
                    received.add(r);
                }
            });
            return unsent(orderIds) == 0 && received.size() >= 5;
        });
        long deliverMillis = (System.nanoTime() - upAt) / 1_000_000;
        received.addAll(drain(orderIds, Duration.ofSeconds(2))); // late duplicates would show up here

        Set<String> eventIds = new HashSet<>();
        for (ConsumerRecord<String, String> r : received) {
            eventIds.add(json.readTree(r.value()).get("eventId").asText());
        }
        Integer maxAttempts = jdbc.queryForObject("SELECT max(attempts) FROM outbox WHERE aggregate_id::text = ANY(?)",
                Integer.class, (Object) orderIds.toArray(String[]::new));
        System.out.printf("=== INCIDENT 003 fix: Kafka down for %d ms; after unpause all %d events delivered in %d ms,"
                + " records on topic %d, distinct eventIds %d, max attempts %d ===%n%n",
                downMillis, orderIds.size(), deliverMillis, received.size(), eventIds.size(), maxAttempts);

        assertThat(unsent(orderIds)).isZero();
        assertThat(received).as("one record per order").hasSize(5);
        assertThat(eventIds).hasSize(5);
        assertThat(received).extracting(ConsumerRecord::key).containsExactlyInAnyOrderElementsOf(orderIds);
    }

    @Test
    void twoRelaysAtOnceNeverPublishTheSameRowTwice() throws Exception {
        List<String> aggregates = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < 50; i++) {
                UUID aggregate = UUID.randomUUID();
                aggregates.add(aggregate.toString());
                outbox.add(OrderCreated.TOPIC, aggregate, "OutboxTest", 1, Map.of("n", i));
            }
        });

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> relays = new ArrayList<>();
        for (int r = 0; r < 2; r++) {
            relays.add(pool.submit(() -> {
                start.await();
                int published = 0;
                int n;
                while ((n = relay.relayBatch()) > 0) {
                    published += n;
                }
                return published;
            }));
        }
        start.countDown();
        int relayA = relays.get(0).get(60, TimeUnit.SECONDS);
        int relayB = relays.get(1).get(60, TimeUnit.SECONDS);
        pool.shutdown();
        awaitTrue(Duration.ofSeconds(30), () -> unsent(aggregates) == 0); // the scheduled relay may still be on its last batch

        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        awaitTrue(Duration.ofSeconds(30), () -> {
            received.addAll(drain(aggregates, Duration.ofMillis(500)));
            return received.size() >= 50;
        });
        received.addAll(drain(aggregates, Duration.ofSeconds(2)));
        Set<String> eventIds = new HashSet<>();
        for (ConsumerRecord<String, String> r : received) {
            eventIds.add(json.readTree(r.value()).get("eventId").asText());
        }
        System.out.printf("%n=== SKIP LOCKED: 50 rows, relay A published %d, relay B %d, scheduled relay %d (same code, on its timer);"
                + " records on topic %d, distinct eventIds %d ===%n%n",
                relayA, relayB, 50 - relayA - relayB, received.size(), eventIds.size());

        assertThat(received).as("each row published exactly once").hasSize(50);
        assertThat(eventIds).hasSize(50);
    }

    @Test
    void whenTheOutboxInsertFailsTheOrderIsRolledBackAndStockReleased() {
        CATALOG.product(1, "10.00", 5);
        doThrow(new IllegalStateException("simulated outbox failure")).when(outbox)
                .add(anyString(), any(), anyString(), anyInt(), any());
        UUID user = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = createOrder(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, user))
                .as("no order without its event").isZero();
        assertThat(CATALOG.stock(1)).as("reserved stock given back").isEqualTo(5);
    }

    @Test
    void addingAnOutboxRowWithoutATransactionFails() {
        assertThatThrownBy(() -> outbox.add(OrderCreated.TOPIC, UUID.randomUUID(), "OutboxTest", 1, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
    }

    private int unsent(List<String> aggregateIds) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id::text = ANY(?) AND sent_at IS NULL",
                Integer.class, (Object) aggregateIds.toArray(String[]::new));
    }

    private List<ConsumerRecord<String, String>> drain(List<String> keys, Duration duration) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) {
            consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (keys.contains(r.key())) {
                    found.add(r);
                }
            });
        }
        return found;
    }

    private static void awaitTrue(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(200);
        }
    }

    private ResponseEntity<Map<String, Object>> createOrder(UUID user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", user.toString());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("items", List.of(Map.of("productId", 1, "quantity", 1)));
        return rest.exchange("/api/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JSON);
    }
}
