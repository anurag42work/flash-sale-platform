package com.flashsale.order.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ttl         how long a key (and its stored response) is kept; a retry after this creates a new order
 * @param cleanupCron when the cleanup job runs (Spring cron: second minute hour day month weekday)
 */
@ConfigurationProperties("idempotency")
public record IdempotencyProperties(Duration ttl, String cleanupCron) {
}
