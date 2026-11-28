package com.flashsale.payment.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the fake payment provider (env PAYMENT_PROVIDER_LATENCY_MS / _FAILURE_RATE / _ERROR_RATE).
 *
 * @param latencyMs          how long every charge takes, to simulate a slow provider (incident 005)
 * @param failureRatePercent share of charges that are declined (a business answer), 0–100
 * @param errorRatePercent   share of calls that fail with {@link ProviderUnavailableException} (a technical error,
 *                           like HTTP 503), 0–100
 */
@ConfigurationProperties("payment.provider")
public record ProviderProperties(int latencyMs, int failureRatePercent, int errorRatePercent) {

    public ProviderProperties {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("payment.provider.latency-ms must be >= 0, was " + latencyMs);
        }
        requirePercent("failure-rate-percent", failureRatePercent);
        requirePercent("error-rate-percent", errorRatePercent);
    }

    private static void requirePercent(String name, int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("payment.provider." + name + " must be 0-100, was " + value);
        }
    }
}
