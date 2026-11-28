# Incident 004: Poison messages and failing events are silently dropped

| | |
|---|---|
| Status | Reproduced (step 2.4, commit 1). Fix: backoff + dead-letter topics (step 2.4, commit 2) |
| Services | every Kafka consumer: payment-service (`order.created`), order-service (`payment.*`), catalog-service (`order.failed`) |
| Branch | `incident/004-poison-message` |
| Test | `payment-service/src/test/java/com/flashsale/payment/PoisonMessageIT.java` |

## Symptom
Something publishes a message the consumer can't handle. It could be malformed JSON (a bug in a producer, a
manual test, a schema change), or a valid event that fails while processing (for example, the database is down
for a few seconds). The consumer logs one ERROR line and **moves on**. The event is gone: there's no payment, the
order stays PENDING, and nothing anywhere holds a copy of the message to retry later.

**Correction to the plan text:** the five-day plan says to show the consumer "retrying forever and blocking the
partition". That isn't what happens with our versions. Spring Kafka 3.x's default `DefaultErrorHandler`
uses `FixedBackOff(0 ms, 9 retries)`: 10 attempts with no pause, then the record is skipped. The partition is
**not** blocked: the next message is processed. The real damage is the opposite one, **silent data loss**.
(Infinite retries happen only if someone configures them. Then a single poison message does block its partition.)

## How it was reproduced
- Testcontainers Postgres 16 + Kafka 3.9.1, payment-service as built in step 2.3, with no custom error handling.
- **Poison:** publish `{"eventId": "not-even-json` (truncated JSON) to `order.created`, and right behind it, with the
  same key (same partition), a valid `OrderCreated`. The test counts the listener's parse attempts (Mockito spy on
  the `ObjectMapper`) and reads `order.created.DLT`.
- **Transient:** a spy makes `PaymentService.handle` throw `TransientDataAccessResourceException` for one event
  during an 8 s window (a simulated DB outage). After the window it would succeed.
- Expected (what the tests assert): the poison message is parked on `order.created.DLT` after **1** attempt, and the
  transient event is parked on the DLT (not lost), so it can be replayed after the outage.
- Command:
  ```
  ./mvnw -pl payment-service -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
         -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=PoisonMessageIT
  ```

## Evidence (measured 2026-11-28, before the fix)
Machine: laptop, Intel i5-8265U (4 cores / 8 threads), 8 GB RAM, Windows 11. Docker/WSL2 has 2 CPUs and 3 GB.

```
ERROR o.s.kafka.listener.DefaultErrorHandler : Backoff FixedBackOff{interval=0, currentAttempts=10, maxAttempts=9} exhausted for order.created-0@0
=== INCIDENT 004 (poison): attempts=10 (expected 1), on DLT=0 (expected 1), next message processed ... ===
ERROR o.s.kafka.listener.DefaultErrorHandler : Backoff FixedBackOff{interval=0, currentAttempts=10, maxAttempts=9} exhausted for order.created-0@2
=== INCIDENT 004 (transient): attempts=10, payment created=false, on DLT=0 (expected 1) ===
```

| Case | Attempts | Kept anywhere? | Result |
|---|---|---|---|
| Malformed JSON | 10, no pause between them | no | dropped; the next message on the partition was processed |
| Valid event, 8 s DB outage | 10, all inside the outage | no | **event lost**: no payment, the order would stay PENDING forever |

(Single run each. The time until the next message was processed also includes the consumer joining its group,
so it isn't reported as a blocking time.)

## Root cause
- No error-handling policy was configured, so every consumer runs with the default: a fixed number of instant
  retries, then "log and skip" (the default recoverer only logs).
- The retries have **no backoff**: 10 attempts happen within milliseconds, so they can't ride out even a short
  outage.
- **All errors are treated the same.** A malformed message is retried 10 times although it can never succeed, and
  a transient failure gets the same short treatment as a bug.
- There's **no dead-letter topic**, so a skipped record exists only as a log line. Nobody can inspect it or replay
  it after the cause is fixed.

## Fix
To be filled in by commit 2 (step 2.4): exponential backoff (3 attempts), then `DeadLetterPublishingRecoverer` to
`<topic>.DLT`. Deserialization and validation errors are not retried. `scripts/dlt.sh` lists and replays.

## Before / after
| | Before (default handler) | After |
|---|---|---|
| Malformed JSON | 10 attempts, dropped | to be measured |
| Transient failure longer than the retries | 10 attempts, lost | to be measured |
