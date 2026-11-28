package com.flashsale.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flashsale.payment.provider.MockPaymentProvider.ChargeResult;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MockPaymentProviderTest {

    private static final BigDecimal AMOUNT = new BigDecimal("20.00");

    @Test
    void zeroPercentApprovesEveryCharge() {
        MockPaymentProvider provider = new MockPaymentProvider(new ProviderProperties(0, 0));

        for (int i = 0; i < 100; i++) {
            assertThat(provider.charge(UUID.randomUUID(), AMOUNT).approved()).isTrue();
        }
    }

    @Test
    void hundredPercentDeclinesEveryChargeWithAReason() {
        MockPaymentProvider provider = new MockPaymentProvider(new ProviderProperties(0, 100));

        ChargeResult result = provider.charge(UUID.randomUUID(), AMOUNT);

        assertThat(result.approved()).isFalse();
        assertThat(result.failureReason()).isEqualTo("DECLINED");
    }

    @Test
    void sameOrderAlwaysGetsTheFirstResult() {
        // 50 %: without the idempotency key, repeated charges of one order would flip between approved/declined.
        MockPaymentProvider provider = new MockPaymentProvider(new ProviderProperties(0, 50), new Random(42));
        UUID order = UUID.randomUUID();
        ChargeResult first = provider.charge(order, AMOUNT);

        Set<ChargeResult> results = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            results.add(provider.charge(order, AMOUNT));
        }

        assertThat(results).containsOnly(first);
    }

    @Test
    void latencyIsApplied() {
        MockPaymentProvider provider = new MockPaymentProvider(new ProviderProperties(50, 0));

        long start = System.nanoTime();
        provider.charge(UUID.randomUUID(), AMOUNT);

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(50);
    }

    @Test
    void invalidSettingsAreRejectedAtStartup() {
        assertThatThrownBy(() -> new ProviderProperties(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderProperties(0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderProperties(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
