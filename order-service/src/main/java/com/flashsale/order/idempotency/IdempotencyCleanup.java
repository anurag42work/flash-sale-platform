package com.flashsale.order.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes keys older than the TTL so the table doesn't grow forever. One DELETE, served by the created_at index.
 * Also clears keys stuck IN_PROGRESS after a crash (they return 409 until then).
 *
 * <p>With several order-service instances each one runs it; that's harmless (the DELETEs are idempotent).
 */
@Component
public class IdempotencyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final JdbcClient jdbc;
    private final IdempotencyProperties properties;
    private final Clock clock;

    IdempotencyCleanup(JdbcClient jdbc, IdempotencyProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${idempotency.cleanup-cron}")
    public int deleteExpired() {
        int deleted = jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(properties.ttl())))
                .update();
        if (deleted > 0) {
            log.info("Deleted {} idempotency keys older than {}", deleted, properties.ttl());
        }
        return deleted;
    }
}
