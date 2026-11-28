package com.flashsale.payment.provider;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stands in for an external payment provider (Stripe, Adyen...). Configurable latency and decline rate.
 *
 * <p>Idempotent per order, like real providers with an idempotency key: charging the same orderId again returns
 * the first result instead of charging twice. That matters because Kafka can deliver the same OrderCreated twice
 * and the charge happens outside our DB transaction. Results are kept in memory, so a restart forgets them
 * (a real provider keeps them on its side).
 */
@Component
public class MockPaymentProvider {

    private final ProviderProperties properties;
    private final RandomGenerator random;
    private final Map<UUID, ChargeResult> resultsByOrder = new ConcurrentHashMap<>();

    @Autowired // two constructors: tell Spring which one to use (the other is for tests with a fixed random)
    MockPaymentProvider(ProviderProperties properties) {
        this(properties, RandomGenerator.getDefault());
    }

    MockPaymentProvider(ProviderProperties properties, RandomGenerator random) {
        this.properties = properties;
        this.random = random;
    }

    /** @param idempotencyKey the order id: one charge per order, however often it is asked for */
    public ChargeResult charge(UUID idempotencyKey, BigDecimal amount) {
        sleep(properties.latencyMs());
        return resultsByOrder.computeIfAbsent(idempotencyKey, key ->
                random.nextInt(100) < properties.failureRatePercent()
                        ? ChargeResult.declined("DECLINED")
                        : ChargeResult.success());
    }

    private static void sleep(int millis) {
        if (millis == 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the payment provider", e);
        }
    }

    /** @param failureReason null when approved */
    public record ChargeResult(boolean approved, String failureReason) {

        static ChargeResult success() {
            return new ChargeResult(true, null);
        }

        static ChargeResult declined(String reason) {
            return new ChargeResult(false, reason);
        }
    }
}
