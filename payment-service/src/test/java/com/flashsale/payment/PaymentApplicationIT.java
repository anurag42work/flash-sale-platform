package com.flashsale.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashsale.payment.provider.ProviderUnavailableException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
// Close this context after the class: a cached context keeps its Kafka listener and outbox relay running and
// would react to the next test class's events on the shared broker.
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApplicationIT {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthIsUp() {
        var response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void flywayCreatedTheSchema() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder("payments", "processed_events", "outbox");
    }

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @Autowired
    RetryRegistry retries;

    @Autowired
    TimeLimiterRegistry timeLimiters;

    @Autowired
    ThreadPoolBulkheadRegistry bulkheads;

    /** A typo in application.yml would silently fall back to Resilience4j's defaults; pin the values we chose. */
    @Test
    void providerProtectionUsesTheConfiguredValues() {
        CircuitBreakerConfig cb = circuitBreakers.circuitBreaker("paymentProvider").getCircuitBreakerConfig();
        assertThat(cb.getSlidingWindowSize()).isEqualTo(20);
        assertThat(cb.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(cb.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(cb.getSlowCallRateThreshold()).isEqualTo(50f);
        assertThat(cb.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(1500));
        assertThat(cb.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(cb.getRecordExceptionPredicate().test(new ProviderUnavailableException("x"))).isTrue();
        assertThat(cb.getRecordExceptionPredicate().test(new IllegalStateException("bug"))).isFalse();

        RetryConfig retry = retries.retry("paymentProvider").getRetryConfig();
        assertThat(retry.getMaxAttempts()).isEqualTo(3);
        assertThat(retry.getExceptionPredicate().test(new ProviderUnavailableException("x"))).isTrue();
        assertThat(retry.getExceptionPredicate().test(CallNotPermittedException.createCallNotPermittedException(
                circuitBreakers.circuitBreaker("paymentProvider")))).as("open breaker is not retried").isFalse();
        // Exponential with jitter: first wait 200 ms +-50 %, second 400 ms +-50 %, and not always the same value.
        Set<Long> firstWaits = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            long first = retry.getIntervalBiFunction().apply(1, null);
            long second = retry.getIntervalBiFunction().apply(2, null);
            assertThat(first).isBetween(100L, 300L);
            assertThat(second).isBetween(200L, 600L);
            firstWaits.add(first);
        }
        assertThat(firstWaits).as("jitter").hasSizeGreaterThan(1);

        assertThat(timeLimiters.timeLimiter("paymentProvider").getTimeLimiterConfig().getTimeoutDuration())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(bulkheads.bulkhead("paymentProvider").getBulkheadConfig().getMaxThreadPoolSize()).isEqualTo(3);
        assertThat(bulkheads.bulkhead("paymentProvider").getBulkheadConfig().getQueueCapacity()).isEqualTo(3);
    }
}
