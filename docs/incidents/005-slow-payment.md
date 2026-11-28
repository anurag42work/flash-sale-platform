# Incident 005: A slow payment provider leaves orders PENDING behind a growing queue

| | |
|---|---|
| Status | Fixed (step 2.5): Resilience4j timeout, retry, circuit breaker, bulkhead around the provider; fallback PROVIDER_UNAVAILABLE |
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

## Fix (step 2.5, commit 2)
Options compared in `docs/decisions/006-provider-resilience.md`.

`PaymentService` no longer calls the provider directly. It calls `ResilientPaymentGateway.charge`, which wraps one
charge (outermost first):

```
Retry( CircuitBreaker( TimeLimiter( ThreadPoolBulkhead( provider.charge ) ) ) )
```

- **Fallback:** a timeout, provider-unavailable error, full bulkhead or open circuit ends as a decline with reason
  `PROVIDER_UNAVAILABLE`. The payment is FAILED and `payment.failed` is published. order-service now stores the
  reason it receives (`PAYMENT_PROVIDER_UNAVAILABLE` instead of always `PAYMENT_DECLINED`), the order is FAILED,
  and catalog releases the stock (steps 2.3b/c, unchanged).
- A decline from the provider is a normal answer: it isn't an exception, it's never retried, and it doesn't count
  against the circuit.
- Any other exception (a bug) is re-thrown to Kafka's error handler (retry, then DLT, incident 004).
- Consumer: listener concurrency 3 (one thread per partition) and `max-poll-records` 10.
- Mock provider: new `PAYMENT_PROVIDER_ERROR_RATE` (%) throws `ProviderUnavailableException` (like HTTP 503), so
  the circuit can be tested on errors, not only on slowness.
- Library: `io.github.resilience4j:resilience4j-spring-boot3` 2.2.0 (version from the Spring Cloud BOM). Values are
  in `payment-service/src/main/resources/application.yml`, and `PaymentApplicationIT` pins them, so a typo can't
  fall back to the library defaults unnoticed.

### Every value, why, and what goes wrong without it
| Setting | Value | Why this value | Without it / too far the other way |
|---|---|---|---|
| `timelimiter.timeout-duration` | 2 s | A healthy charge takes ~100 ms (mock default), so 2 s only cuts off a provider that is clearly unwell | No timeout: each order waits as long as the provider takes (measured before: 1 order per 5 s). Too short: healthy-but-slowish charges fail |
| `timelimiter.cancel-running-future` | true | Ask the call to stop when we give up | A `CompletableFuture` can't interrupt its thread, so the abandoned call still holds a bulkhead thread until the provider answers (why the bulkhead matters) |
| `circuitbreaker.sliding-window-type`, `sliding-window-size` | COUNT_BASED, 20 | Judge the provider on its last 20 calls; count-based is predictable at low or bursty traffic | Too small: one unlucky moment opens the circuit. Too large: reacts slowly to an outage |
| `circuitbreaker.minimum-number-of-calls` | 10 | No verdict from 1–2 calls | 1: the first timeout after a deploy fails every order for 10 s. Very high: never opens; measured with 1000: 47 of 50 decided in 30 s (vs 50 of 50 in 5.9–6.3 s), circuit never opened |
| `circuitbreaker.failure-rate-threshold` | 50 % | Clearly broken, not a few random errors | Lower: opens on normal noise. Higher: keeps hammering a mostly-down provider |
| `circuitbreaker.slow-call-duration-threshold` | 1.5 s | Below the 2 s timeout, so "slow but answering" is caught before calls start timing out | Without it, a provider at 1.9 s never trips the breaker (no failures), but every charge ties up a thread for ~2 s (unit test: slow successful calls open the circuit) |
| `circuitbreaker.slow-call-rate-threshold` | 50 % | Same reasoning as the failure rate | Same as the failure rate |
| `circuitbreaker.wait-duration-in-open-state` | 10 s | Gives the provider room to recover; short enough that a 30 s outage costs a few cycles, not the sale | Too short: hammers a provider that is still down. Too long: orders keep failing although it's back |
| `circuitbreaker.permitted-number-of-calls-in-half-open-state` | 3 | A few probes, so one lucky call doesn't close it | 1: a single success closes the circuit on a flaky provider |
| `circuitbreaker.record-exceptions` | Timeout, ProviderUnavailable, BulkheadFull | Only provider trouble counts against the provider (pinned in `PaymentApplicationIT`: a bug is not recorded) | Default (all exceptions): a bug in our code would open the circuit and fail every order |
| `retry.max-attempts` | 3 | Rides out a blip (unit test: one 503, then success) | 1: every blip fails an order. More: multiplies load on a struggling provider and the time per order (each attempt can cost 2 s) |
| `retry.wait-duration`, `exponential-backoff-multiplier` | 200 ms, ×2 | Short pauses: 200 ms, then 400 ms | No pause: retries hit the provider at the moment it's failing |
| `retry.enable-randomized-wait`, `randomized-wait-factor` | true, 0.5 | ±50 % jitter (checked: first wait between 100 and 300 ms and not constant) | Without jitter, many consumers retry in lock-step and hit the provider in waves |
| `retry.retry-exceptions` | Timeout, ProviderUnavailable, BulkheadFull | Transient only | Retrying `CallNotPermittedException` would defeat the open circuit. Retrying a decline would try to charge again for nothing |
| `thread-pool-bulkhead` core / max threads | 3 / 3 | Same as the consumer threads; caps provider calls in flight | Without it: abandoned timed-out calls pile up in an unbounded pool (one thread per call) |
| `thread-pool-bulkhead.queue-capacity` | 3 | A call can wait briefly for a thread (the wait counts toward the 2 s timeout) | Very small: rejects during normal bursts. Large: hides the problem and delays failure (unit test: 1 thread + 1 queued, so the 3rd caller fails fast) |
| `listener.concurrency` | 3 | One thread per `order.created` partition | 1: one slow charge holds up all partitions (before: 1 thread) |
| `consumer.max-poll-records` | 10 | Worst case per record ~7 s (3 × 2 s + backoff), so 10 records ≈ 70 s, well under `max.poll.interval.ms` 300 s | 500 (default) × 7 s is far above 300 s: Kafka declares the consumer dead, rebalances and redelivers the poll (reasoned, not reproduced) |

The values are starting points, chosen by reasoning plus the tests here. Day 4 load tests on the VM will show
whether they need tuning.

## Before / after (measured 2026-11-28, same machine, single runs)
| Scenario (`SlowProviderIT`: provider 5000 ms, 50 orders, 30 s deadline) | Decided | Still PENDING | Time |
|---|---|---|---|
| Before (no protection, 1 consumer thread) | 5 (COMPLETED) | 45 | 30 s deadline hit |
| After, first run (test topic had only 1 partition, see below) | 50 (FAILED `PROVIDER_UNAVAILABLE`) | 0 | 24.3 s |
| After, topic with 3 partitions like production (2 runs) | 50 (FAILED `PROVIDER_UNAVAILABLE`) | 0 | **5.9 s, 6.3 s** |
| After, but circuit breaker effectively off (`minimum-number-of-calls=1000`) | 47 | 3 | 30 s deadline hit |

**Test setup finding:** nobody declared `order.created` in payment-service's tests (order-service owns it), so the
broker auto-created it with 1 partition and only one of the 3 listener threads got work. The test configuration now
declares it with 3 partitions, like production. In the 1-partition run, each order cost ~6.8 s (3 attempts of 2 s +
backoff) until 10 calls were recorded and the circuit opened. After that, orders failed in milliseconds.

Unit tests (`ResilientPaymentGatewayTest`, 9):
- slow → timeout (2 attempts in < 450 ms instead of 500 ms each);
- a decline is not retried; a 503 is retried with backoff (≥ 150 ms across the 3 attempts); a blip is ridden out;
- failures open the circuit, and then the provider isn't called; slow but successful calls open it too;
- half-open → closed after recovery; a half-open failure → open again;
- a full bulkhead fails the third caller fast.

Command: `./mvnw -pl payment-service -am verify`

## Trade-offs and open points
- **Failing is a business choice.** While the provider is unhealthy, orders fail and stock comes back for other
  buyers. The alternative is to pause the consumer while the circuit is open, so orders wait instead of failing
  (decision 006).
- **A timeout doesn't mean "not charged".** A real provider may complete a charge after we gave up. We then mark the
  payment FAILED and release the stock while the customer has been charged. This needs a reconciliation job that
  asks the provider for the final status of `PROVIDER_UNAVAILABLE` payments (`docs/todo.md`). The mock even shows it:
  its idempotency map remembers the late result.
- The gateway → downstream path (the gateway returns 500 when a service is down) is a separate item (`docs/todo.md`).
