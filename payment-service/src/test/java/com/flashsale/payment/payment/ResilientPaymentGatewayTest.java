package com.flashsale.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flashsale.payment.provider.MockPaymentProvider;
import com.flashsale.payment.provider.MockPaymentProvider.ChargeResult;
import com.flashsale.payment.provider.ProviderUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The provider protection on its own: real Resilience4j objects with tiny durations, a Mockito stand-in for the
 * provider. No Spring, no Kafka. The production values live in application.yml (checked in PaymentApplicationIT).
 */
class ResilientPaymentGatewayTest {

    private static final ChargeResult APPROVED = new ChargeResult(true, null);
    private static final BigDecimal AMOUNT = new BigDecimal("20.00");

    private final MockPaymentProvider provider = mock(MockPaymentProvider.class);
    private ThreadPoolBulkhead bulkhead;

    @AfterEach
    void closeBulkhead() throws Exception {
        if (bulkhead != null) {
            bulkhead.close();
        }
    }

    @Test
    void slowProviderTimesOutAndTheChargeEndsAsProviderUnavailable() {
        slow(500, APPROVED);
        ResilientPaymentGateway gateway = gateway(lenientBreaker(), retry(2, IntervalFunction.of(10)),
                Duration.ofMillis(100), bulkhead(4, 4));

        long start = System.nanoTime();
        ChargeResult result = gateway.charge(UUID.randomUUID(), AMOUNT);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isEqualTo(new ChargeResult(false, ResilientPaymentGateway.PROVIDER_UNAVAILABLE));
        assertThat(millis).as("2 attempts x 100 ms timeout, not 500 ms per attempt").isLessThan(450);
        verify(provider, times(2)).charge(any(), any());
    }

    @Test
    void declineIsAnAnswerNotAnErrorAndIsNotRetried() {
        doReturn(new ChargeResult(false, "DECLINED")).when(provider).charge(any(), any());
        ResilientPaymentGateway gateway = gateway(lenientBreaker(), retry(3, IntervalFunction.of(10)),
                Duration.ofSeconds(1), bulkhead(4, 4));

        assertThat(gateway.charge(UUID.randomUUID(), AMOUNT).failureReason()).isEqualTo("DECLINED");
        verify(provider, times(1)).charge(any(), any());
    }

    @Test
    void unavailableIsRetriedWithExponentialBackoffThenFallsBack() {
        doThrow(new ProviderUnavailableException("503")).when(provider).charge(any(), any());
        ResilientPaymentGateway gateway = gateway(lenientBreaker(),
                retry(3, IntervalFunction.ofExponentialBackoff(50, 2)), Duration.ofSeconds(1), bulkhead(4, 4));

        long start = System.nanoTime();
        ChargeResult result = gateway.charge(UUID.randomUUID(), AMOUNT);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.failureReason()).isEqualTo(ResilientPaymentGateway.PROVIDER_UNAVAILABLE);
        verify(provider, times(3)).charge(any(), any());
        assertThat(millis).as("waited 50 ms + 100 ms between the attempts").isGreaterThanOrEqualTo(150);
    }

    @Test
    void aBlipIsRiddenOutByTheRetry() {
        doThrow(new ProviderUnavailableException("503")).doReturn(APPROVED).when(provider).charge(any(), any());
        ResilientPaymentGateway gateway = gateway(lenientBreaker(), retry(3, IntervalFunction.of(10)),
                Duration.ofSeconds(1), bulkhead(4, 4));

        assertThat(gateway.charge(UUID.randomUUID(), AMOUNT)).isEqualTo(APPROVED);
        verify(provider, times(2)).charge(any(), any());
    }

    @Test
    void failingProviderOpensTheCircuitAndThenIsNotCalledAtAll() {
        doThrow(new ProviderUnavailableException("503")).when(provider).charge(any(), any());
        CircuitBreaker breaker = breaker(Duration.ofSeconds(60), Duration.ofSeconds(10));
        ResilientPaymentGateway gateway = gateway(breaker, retry(1, IntervalFunction.of(10)), Duration.ofSeconds(1),
                bulkhead(4, 4));

        for (int i = 0; i < 4; i++) {
            gateway.charge(UUID.randomUUID(), AMOUNT);
        }
        assertThat(breaker.getState()).isEqualTo(State.OPEN);
        clearInvocations(provider);

        long start = System.nanoTime();
        ChargeResult result = gateway.charge(UUID.randomUUID(), AMOUNT);

        assertThat(result.failureReason()).isEqualTo(ResilientPaymentGateway.PROVIDER_UNAVAILABLE);
        assertThat((System.nanoTime() - start) / 1_000_000).as("fails fast").isLessThan(100);
        verify(provider, never()).charge(any(), any());
    }

    @Test
    void slowButSuccessfulCallsAlsoOpenTheCircuit() {
        slow(80, APPROVED);
        CircuitBreaker breaker = breaker(Duration.ofSeconds(60), Duration.ofMillis(50));
        ResilientPaymentGateway gateway = gateway(breaker, retry(1, IntervalFunction.of(10)), Duration.ofSeconds(1),
                bulkhead(4, 4));

        for (int i = 0; i < 4; i++) {
            assertThat(gateway.charge(UUID.randomUUID(), AMOUNT)).isEqualTo(APPROVED);
        }

        assertThat(breaker.getState()).as("4 of 4 calls slower than 50 ms").isEqualTo(State.OPEN);
    }

    @Test
    void afterTheWaitTheCircuitGoesHalfOpenAndClosesWhenTheProviderIsBack() throws Exception {
        doThrow(new ProviderUnavailableException("503")).when(provider).charge(any(), any());
        CircuitBreaker breaker = breaker(Duration.ofMillis(200), Duration.ofSeconds(10));
        ResilientPaymentGateway gateway = gateway(breaker, retry(1, IntervalFunction.of(10)), Duration.ofSeconds(1),
                bulkhead(4, 4));
        for (int i = 0; i < 4; i++) {
            gateway.charge(UUID.randomUUID(), AMOUNT);
        }
        assertThat(breaker.getState()).isEqualTo(State.OPEN);

        doReturn(APPROVED).when(provider).charge(any(), any()); // the provider recovers
        Thread.sleep(250); // wait-duration-in-open-state is over

        assertThat(gateway.charge(UUID.randomUUID(), AMOUNT)).isEqualTo(APPROVED);
        assertThat(breaker.getState()).as("first test call let through").isEqualTo(State.HALF_OPEN);
        assertThat(gateway.charge(UUID.randomUUID(), AMOUNT)).isEqualTo(APPROVED);
        assertThat(breaker.getState()).as("both permitted test calls worked").isEqualTo(State.CLOSED);
    }

    @Test
    void halfOpenFailureOpensTheCircuitAgain() throws Exception {
        doThrow(new ProviderUnavailableException("503")).when(provider).charge(any(), any());
        CircuitBreaker breaker = breaker(Duration.ofMillis(200), Duration.ofSeconds(10));
        ResilientPaymentGateway gateway = gateway(breaker, retry(1, IntervalFunction.of(10)), Duration.ofSeconds(1),
                bulkhead(4, 4));
        for (int i = 0; i < 4; i++) {
            gateway.charge(UUID.randomUUID(), AMOUNT);
        }
        Thread.sleep(250);

        gateway.charge(UUID.randomUUID(), AMOUNT); // still failing
        gateway.charge(UUID.randomUUID(), AMOUNT);

        assertThat(breaker.getState()).isEqualTo(State.OPEN);
    }

    @Test
    void fullBulkheadFailsFastInsteadOfQueueingWithoutLimit() throws Exception {
        slow(300, APPROVED);
        ResilientPaymentGateway gateway = gateway(lenientBreaker(), retry(1, IntervalFunction.of(10)),
                Duration.ofSeconds(2), bulkhead(1, 1)); // 1 running + 1 waiting
        ExecutorService callers = Executors.newFixedThreadPool(3);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ChargeResult>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(callers.submit(() -> {
                go.await();
                return gateway.charge(UUID.randomUUID(), AMOUNT);
            }));
        }
        go.countDown();

        int approved = 0;
        int unavailable = 0;
        for (Future<ChargeResult> f : results) {
            ChargeResult r = f.get(5, TimeUnit.SECONDS);
            if (r.approved()) {
                approved++;
            } else {
                unavailable++;
            }
        }
        callers.shutdown();

        assertThat(approved).as("running + queued").isEqualTo(2);
        assertThat(unavailable).as("third caller: bulkhead full").isEqualTo(1);
    }

    // --- helpers ---

    private void slow(long millis, ChargeResult answer) {
        doAnswer(call -> {
            Thread.sleep(millis);
            return answer;
        }).when(provider).charge(any(), any());
    }

    private ResilientPaymentGateway gateway(CircuitBreaker breaker, Retry retry, Duration timeout,
            ThreadPoolBulkhead pool) {
        TimeLimiter limiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(timeout)
                .cancelRunningFuture(true).build());
        return new ResilientPaymentGateway(provider, breaker, retry, limiter, pool);
    }

    /** Window of 4 calls, opens at 50 % failures or 50 % slow calls; 2 test calls when half-open. */
    private static CircuitBreaker breaker(Duration waitInOpen, Duration slowCall) {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(slowCall)
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(waitInOpen)
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(TimeoutException.class, ProviderUnavailableException.class,
                        BulkheadFullException.class)
                .build());
    }

    /** Never opens in these tests: isolates timeout / retry / bulkhead behaviour. */
    private static CircuitBreaker lenientBreaker() {
        return CircuitBreaker.of("lenient", CircuitBreakerConfig.custom().minimumNumberOfCalls(1000).build());
    }

    private static Retry retry(int attempts, IntervalFunction interval) {
        return Retry.of("test", RetryConfig.custom()
                .maxAttempts(attempts)
                .intervalFunction(interval)
                .retryExceptions(TimeoutException.class, ProviderUnavailableException.class,
                        BulkheadFullException.class)
                .build());
    }

    private ThreadPoolBulkhead bulkhead(int threads, int queue) {
        bulkhead = ThreadPoolBulkhead.of("test", ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(threads)
                .maxThreadPoolSize(threads)
                .queueCapacity(queue)
                .build());
        return bulkhead;
    }
}
