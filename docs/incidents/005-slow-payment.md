# Incident 005: A slow payment provider leaves orders PENDING behind a growing queue

| | |
|---|---|
| Status | Reproduced (step 2.5, commit 1). Fix: Resilience4j around the provider call (step 2.5, commit 2) |
| Service | payment-service, `OrderCreatedListener` → `MockPaymentProvider.charge` |
| Branch | `incident/005-slow-payment` |
| Test | `payment-service/src/test/java/com/flashsale/payment/SlowProviderIT.java` |

## Symptom
During a flash sale the payment provider slows down (5 s per charge instead of ~100 ms). Orders keep arriving,
but payment-service handles them one at a time and waits the full 5 s for each. Orders stay PENDING for minutes,
their stock stays reserved (other buyers see "out of stock"), and the queue of unprocessed `order.created`
events keeps growing.

## How it was reproduced
- Testcontainers Postgres 16 + Kafka 3.9.1, payment-service as built in step 2.4.
- `payment.provider.latency-ms=5000`, decline rate 0.
- The test publishes 50 `OrderCreated` events (random keys, spread over the 3 partitions), then counts payment
  rows (= orders with a decision) for up to 30 s.
- Expected (what the test asserts): all 50 orders decided within 30 s.
- Command:
  ```
  ./mvnw -pl payment-service -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
         -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=SlowProviderIT
  ```

## Evidence (measured 2026-11-28, before the fix)
Machine: laptop, Intel i5-8265U (4 cores / 8 threads), 8 GB RAM, Windows 11. Docker/WSL2 has 2 CPUs and 3 GB.

```
=== INCIDENT 005: provider 5000 ms, 50 orders -> decided 5, still PENDING 45 after 30324 ms;
    payments by status [{status=COMPLETED, reason=-, n=5}] ===
```

| Orders | Decided after 30 s | Still PENDING | Throughput |
|---|---|---|---|
| 50 | 5 (all COMPLETED) | 45 | 1 order per 5 s |

At that rate, 50 orders need about 250 s. That's a calculation from the measured rate, not a measurement.
Every new order waits behind all of them. Single run.

## Root cause
1. **No timeout on the provider call.** The consumer waits as long as the provider takes.
2. **One consumer thread for all 3 partitions** (listener concurrency 1). Calls happen strictly one after another,
   so the provider's latency becomes our throughput limit.
3. **Nothing notices the provider is unhealthy.** Every order pays the full 5 s again. Nobody fails fast, and
   nobody gives the provider a break.
4. **A hidden second risk (reasoned, not reproduced here):** one poll returns up to `max.poll.records` = 500
   records. If handling them takes longer than `max.poll.interval.ms` (300 s; 500 × 5 s = 2500 s), Kafka decides
   the consumer is dead, rebalances, and redelivers the uncommitted records. The backlog then never drains.

## Fix
To be filled in by commit 2: Resilience4j TimeLimiter, Retry (backoff + jitter, transient errors only),
CircuitBreaker (failure rate + slow-call rate), Bulkhead; fallback FAILED `PROVIDER_UNAVAILABLE` → stock released.
Plus a table of every value chosen.

## Before / after
| | Before | After |
|---|---|---|
| 50 orders, provider 5000 ms: decided within 30 s | 5 of 50 | to be measured |
