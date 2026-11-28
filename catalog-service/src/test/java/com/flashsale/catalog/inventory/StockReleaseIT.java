package com.flashsale.catalog.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.catalog.TestcontainersConfiguration;
import com.flashsale.common.event.EventEnvelope;
import com.flashsale.common.event.OrderFailed;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Saga compensation: order.failed in → the order's reserved stock is given back, once. Real Postgres and Kafka.
 * Release ADDS stock, so a redelivered event processed twice would create stock that never existed.
 */
@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listener running and would react to the
// next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest
class StockReleaseIT {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Test
    void orderFailedReleasesEachItemOnceEvenIfDeliveredTwice() throws Exception {
        long a = product(8); // 10 at the start, 2 reserved by the failed order
        long b = product(4); // 5 at the start, 1 reserved
        UUID orderId = UUID.randomUUID();
        EventEnvelope<OrderFailed> event = new EventEnvelope<>(UUID.randomUUID(), OrderFailed.TYPE,
                OrderFailed.VERSION, Instant.now(),
                new OrderFailed(orderId, "PAYMENT_DECLINED", List.of(new OrderFailed.Item(a, 2), new OrderFailed.Item(b, 1))));

        publish(orderId, event);
        publish(orderId, event); // redelivery: same eventId
        // A skipped duplicate leaves no trace: wait for a marker on the same key (same partition, processed after it).
        EventEnvelope<OrderFailed> marker = new EventEnvelope<>(UUID.randomUUID(), OrderFailed.TYPE,
                OrderFailed.VERSION, Instant.now(), new OrderFailed(orderId, "MARKER", List.of()));
        publish(orderId, marker);
        awaitTrue(() -> processed(marker.eventId()));

        System.out.printf("%n=== SAGA: OrderFailed delivered 2x -> stock A %d (expected 10), B %d (expected 5) ===%n%n",
                available(a), available(b));
        assertThat(available(a)).isEqualTo(10);
        assertThat(available(b)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                event.eventId())).isEqualTo(1);
    }

    @Test
    void unknownProductIsLoggedAndTheOtherItemsAreStillReleased() throws Exception {
        long a = product(3);
        UUID orderId = UUID.randomUUID();
        EventEnvelope<OrderFailed> event = new EventEnvelope<>(UUID.randomUUID(), OrderFailed.TYPE,
                OrderFailed.VERSION, Instant.now(),
                new OrderFailed(orderId, "PAYMENT_DECLINED",
                        List.of(new OrderFailed.Item(999_999_999L, 1), new OrderFailed.Item(a, 2))));

        publish(orderId, event);
        awaitTrue(() -> processed(event.eventId()));

        assertThat(available(a)).isEqualTo(5);
    }

    private long product(int available) {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbc.queryForObject("INSERT INTO products (sku, name, price) VALUES (?, ?, 9.99) RETURNING id",
                Long.class, sku, "Flash " + sku);
        jdbc.update("INSERT INTO inventory (product_id, available) VALUES (?, ?)", id, available);
        return id;
    }

    private int available(long productId) {
        return jdbc.queryForObject("SELECT available FROM inventory WHERE product_id = ?", Integer.class, productId);
    }

    private boolean processed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                eventId) == 1;
    }

    private void publish(UUID key, EventEnvelope<OrderFailed> event) throws Exception {
        kafka.send(OrderFailed.TOPIC, key.toString(), json.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + DEADLINE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met within " + DEADLINE);
            }
            Thread.sleep(200);
        }
    }
}
