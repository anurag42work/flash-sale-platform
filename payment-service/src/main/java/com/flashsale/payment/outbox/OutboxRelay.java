package com.flashsale.payment.outbox;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes unsent outbox rows to Kafka. Runs on a schedule in every payment-service instance.
 * Same code as order-service's OutboxRelay, copied on purpose (each service owns its outbox; see
 * docs/decisions/004-saga-stock-release.md). Design: docs/decisions/003-dual-write.md.
 *
 * <p>One run = one transaction: lock up to batchSize unsent rows with {@code FOR UPDATE SKIP LOCKED}, publish each
 * and wait for Kafka's ack, mark it sent, commit. SKIP LOCKED makes several instances safe: rows locked by
 * another relay are skipped instead of waited for, so no row is published by two relays at the same time.
 *
 * <p>At-least-once: if the process dies after Kafka acked but before the commit, the row is still unsent and is
 * published again later. Consumers drop the duplicate by eventId.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties properties;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
            OutboxProperties properties) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval}")
    void poll() {
        relayBatch();
    }

    /** @return how many events were published in this run */
    public int relayBatch() {
        Integer sent = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                    SELECT id, aggregate_id, topic, payload::text AS payload
                    FROM outbox
                    WHERE sent_at IS NULL
                    ORDER BY created_at
                    LIMIT :batch
                    FOR UPDATE SKIP LOCKED
                    """)
                    .param("batch", properties.batchSize())
                    .query(Row.class)
                    .list();
            int published = 0;
            for (Row row : rows) {
                try {
                    // .get(): wait for the ack (acks=all) before marking the row sent.
                    kafka.send(row.topic(), row.aggregateId().toString(), row.payload())
                            .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    recordFailure(row, e);
                    break;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    recordFailure(row, e instanceof ExecutionException ? e.getCause() : e);
                    // Stop here: publishing later rows now would put them on Kafka before this one (out of order).
                    break;
                }
                jdbc.sql("UPDATE outbox SET sent_at = now(), attempts = attempts + 1 WHERE id = :id")
                        .param("id", row.id())
                        .update();
                published++;
            }
            return published;
        });
        return sent == null ? 0 : sent;
    }

    private void recordFailure(Row row, Throwable error) {
        String message = String.valueOf(error);
        log.warn("Outbox event {} not published yet, will retry: {}", row.id(), message);
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message)
                .param("id", row.id())
                .update();
    }

    record Row(UUID id, UUID aggregateId, String topic, String payload) {
    }
}
