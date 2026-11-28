# Decision 006: Protecting payment-service from a slow or failing payment provider

**Status:** accepted (step 2.5, incident 005) · **Service:** payment-service

## Problem
The provider call had no timeout, and one consumer thread did all the work. With 5 s per charge, 5 of 50 orders had
a decision after 30 s (`docs/incidents/005-slow-payment.md`). We need every order decided quickly, the provider
protected from a flood while it's unhealthy, and our threads protected from calls that never return.

## 1. Library: Resilience4j via `resilience4j-spring-boot3` (chosen)
- The library Spring points to since Hystrix went into maintenance (2018). It's published by the Resilience4j
  project; version 2.2.0 comes from our Spring Cloud BOM. It provides config in yml, registries, and Micrometer
  metrics (Day 4).
- **Spring Cloud CircuitBreaker** (`spring-cloud-starter-circuitbreaker-resilience4j`): Spring's abstraction over
  the same library. It covers circuit breaker + time limiter, but **not retry**, so we'd mix two styles. It's
  useful when the implementation may change. We'll use its reactive variant in the gateway (a separate todo item).
- **Spring Retry:** maintenance mode, and no circuit breaker or bulkhead. Spring Framework 7's built-in
  resilience features: not available on Boot 3.5.

## 2. Programmatic decorators (chosen) vs `@CircuitBreaker` / `@Retry` annotations
- **Programmatic:** the order (Retry → CircuitBreaker → TimeLimiter → Bulkhead) is visible in one method, and it
  can be unit tested with real objects and tiny durations, without Spring or AOP.
- **Annotations:** less code, but the order is fixed by aspect priorities (easy to get wrong), it needs Spring
  AOP, and the fallback method is matched by signature.

## 3. Decorator order: Retry outermost
- Each retry attempt passes through the circuit breaker, so an open circuit also stops retries
  (`CallNotPermittedException` isn't retryable).
- The TimeLimiter is inside the breaker, so timeouts count as failures. The bulkhead is innermost, so a full pool
  counts as a failure and is retried after a backoff.
- **Alternative:** CircuitBreaker outside Retry. Then the breaker only sees "all 3 attempts failed" as one
  failure, which hides how often the provider fails, and the breaker reacts more slowly.

## 4. ThreadPoolBulkhead (chosen) vs semaphore Bulkhead
- The TimeLimiter needs the call to run on another thread to stop waiting for it. The thread-pool bulkhead is that
  thread pool, with a fixed size and queue.
- A semaphore bulkhead limits concurrent callers on the caller's own thread. It's simpler, but then there's no
  separate thread to time out, so you'd need another executor anyway.
- Limitation: a timed-out call keeps its thread until the provider answers (a `CompletableFuture` can't interrupt
  it). The small pool is what keeps those from piling up.

## 5. Fallback: FAILED `PROVIDER_UNAVAILABLE` (chosen, as in the plan) vs pausing the consumer
- **FAILED:** the order gets a decision within seconds, and its stock goes back on sale. That's good in a flash sale
  where stock is the scarce thing. Cost: customers lose orders whenever the provider has a bad minute, and a
  timed-out charge may still have succeeded (see 6).
- **Pause the consumer while the circuit is open** (Spring Kafka `container.pause()` on a circuit state event):
  orders wait, no customer is failed for our provider's trouble, and they're charged when it's back. Cost: the
  stock stays reserved meanwhile (other buyers see "sold out"), and it's more moving parts (state listener, resume
  logic, health signal). I'd pick this when losing a sale is worse than holding stock: low traffic, expensive items.

## 6. Known gap: timeout ≠ not charged
With orderId as the provider's idempotency key, retries are safe. But "we gave up waiting" isn't "the provider
didn't charge". It needs a reconciliation job: for payments FAILED with `PROVIDER_UNAVAILABLE`, ask the provider for
the final status, then refund or confirm. This goes on the todo list. A real provider integration would also use the
provider's webhooks.

## 7. Retrying timeouts (chosen) vs not
- Retrying a timeout rides out a single slow call. Cost: until the circuit opens, each order can take up to
  3 × 2 s. Measured with 1 partition, that was ~6.8 s per order and 24.3 s to decide 50 orders. With 3 partitions
  it was 5.9 s.
- Not retrying timeouts fails faster under a slow provider, but a single slow call fails the order.
