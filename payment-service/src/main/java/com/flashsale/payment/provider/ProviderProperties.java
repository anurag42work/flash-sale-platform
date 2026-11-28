package com.flashsale.payment.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the fake payment provider (env PAYMENT_PROVIDER_LATENCY_MS / PAYMENT_PROVIDER_FAILURE_RATE).
 *
 * @param latencyMs       how long every charge takes, to simulate a slow provider (incident 005)
 * @param failureRatePercent share of charges that are declined, 0–100
 */
@ConfigurationProperties("payment.provider")
public record ProviderProperties(int latencyMs, int failureRatePercent) {

    public ProviderProperties {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("payment.provider.latency-ms must be >= 0, was " + latencyMs);
        }
        if (failureRatePercent < 0 || failureRatePercent > 100) {
            throw new IllegalArgumentException(
                    "payment.provider.failure-rate-percent must be 0-100, was " + failureRatePercent);
        }
    }
}
