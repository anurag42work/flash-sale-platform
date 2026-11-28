package com.flashsale.payment.payment;

import com.flashsale.payment.provider.MockPaymentProvider;
import com.flashsale.payment.provider.MockPaymentProvider.ChargeResult;
import com.flashsale.payment.provider.ProviderUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only way to call the payment provider (incident 005). Wraps one charge, from the outside in:
 *
 * <pre>
 * Retry( CircuitBreaker( TimeLimiter( ThreadPoolBulkhead( provider.charge ) ) ) )
 * </pre>
 *
 * <ul>
 *   <li><b>ThreadPoolBulkhead</b>: the call runs on a small, separate pool, so at most N provider calls are in flight.
 *       A call that already timed out keeps its thread until the provider answers; the bulkhead stops those from
 *       piling up without limit.</li>
 *   <li><b>TimeLimiter</b>: we stop waiting after a fixed time instead of as long as the provider takes.</li>
 *   <li><b>CircuitBreaker</b>: counts failures and slow calls; when too many, it opens and later calls fail at once
 *       without touching the provider, which gives it time to recover. After a pause it lets a few test calls through
 *       (half-open) and closes again if they work.</li>
 *   <li><b>Retry</b>: outermost, so every attempt goes through the breaker (an open breaker stops the retries too).
 *       Only transient errors are retried, with exponential backoff and jitter.</li>
 * </ul>
 *
 * <p>Fallback: when the provider can't be reached (timeout, unavailable, bulkhead full, breaker open), the charge
 * ends as a decline with reason {@value #PROVIDER_UNAVAILABLE}: the payment is FAILED, the order fails and the stock
 * is released. A decline from the provider is a normal answer: not an error, never retried.
 *
 * <p>Known gap: a timeout means "no answer in time", not "not charged". A real provider may still charge after we
 * gave up; that needs reconciliation with the provider (docs/todo.md).
 */
public class ResilientPaymentGateway {

    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    private static final Logger log = LoggerFactory.getLogger(ResilientPaymentGateway.class);

    private final MockPaymentProvider provider;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final ThreadPoolBulkhead bulkhead;

    public ResilientPaymentGateway(MockPaymentProvider provider, CircuitBreaker circuitBreaker, Retry retry,
            TimeLimiter timeLimiter, ThreadPoolBulkhead bulkhead) {
        this.provider = provider;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.timeLimiter = timeLimiter;
        this.bulkhead = bulkhead;
    }

    public ChargeResult charge(UUID orderId, BigDecimal amount) {
        // bulkhead.executeSupplier throws BulkheadFullException at once when the pool and its queue are full.
        Callable<ChargeResult> timed = TimeLimiter.decorateFutureSupplier(timeLimiter,
                () -> bulkhead.executeSupplier(() -> provider.charge(orderId, amount)).toCompletableFuture());
        Callable<ChargeResult> guarded = CircuitBreaker.decorateCallable(circuitBreaker, timed);
        Callable<ChargeResult> retried = Retry.decorateCallable(retry, guarded);
        try {
            return retried.call();
        } catch (TimeoutException | ProviderUnavailableException | BulkheadFullException
                 | CallNotPermittedException e) {
            log.warn("Payment provider unavailable for order {} (circuit {}): {}", orderId, circuitBreaker.getState(),
                    e.toString());
            return new ChargeResult(false, PROVIDER_UNAVAILABLE);
        } catch (RuntimeException e) {
            throw e; // a bug, not the provider: let Kafka's error handler retry and park it on the DLT
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected checked exception from the provider call", e);
        }
    }
}
