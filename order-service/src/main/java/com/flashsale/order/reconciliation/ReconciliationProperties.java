package com.flashsale.order.reconciliation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * reconciliation.* settings (step 2.6).
 *
 * @param enabled    false switches the schedule off (tests call {@link ReconciliationJob#runOnce()} themselves)
 * @param interval   pause between two runs
 * @param stuckAfter an order PENDING longer than this is checked with payment-service
 * @param batchSize  max orders per run, so one run (and its lease) has a known upper bound
 * @param lockLease  how long a run may hold the lock; must exceed the longest run
 *                   (batchSize × (payment connect + read timeout))
 */
@ConfigurationProperties("reconciliation")
public record ReconciliationProperties(boolean enabled, Duration interval, Duration stuckAfter, int batchSize,
        Duration lockLease) {

    public ReconciliationProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("reconciliation.batch-size must be >= 1, was " + batchSize);
        }
    }
}
