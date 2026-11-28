# Incident 004: Poison messages and failing events are silently dropped

| | |
|---|---|
| Status | Fixed (step 2.4): retry with backoff, then dead-letter topics; bad data straight to the DLT |
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

## Fix (step 2.4, commit 2)
Options compared in `docs/decisions/005-dead-letter-topics.md`.

1. **One error policy for every consumer:** `common-lib/.../kafka/KafkaErrorHandling.deadLetterErrorHandler(...)`,
   registered as the `CommonErrorHandler` bean in payment, order and catalog (`config/KafkaErrorConfig`). Spring
   Boot plugs it into every `@KafkaListener`.
2. **Backoff:** 3 attempts (at once, after 1 s, after 2 s more; this is configuration, not a measurement). The
   partition waits during the retries, so events of one order stay in order.
3. **Dead-letter topic:** after the last attempt, `DeadLetterPublishingRecoverer` publishes the record to
   `<topic>.DLT` (same key and partition number, original value). Headers carry the error class, message and
   stack trace, plus the original topic, partition and offset. Only then does the consumer move on.
   The name is set explicitly. Spring Kafka 3.x's default would be `<topic>-dlt`, and that's how the first run of
   the fix failed (it published to an undeclared topic).
   DLT topics are declared by the consuming service with the same partition count: `order.created.DLT` (payment),
   `payment.completed.DLT` / `payment.failed.DLT` (order), `order.failed.DLT` (catalog).
4. **Bad data is never retried:** Jackson parse errors and the new `InvalidEventException` go to the DLT after
   1 attempt. Every listener now validates what it relies on: `EventEnvelope.requireValid(type)` (eventId, type,
   data), plus per event amount > 0 and items present (payment), orderId (order), and quantities > 0 (catalog;
   a negative quantity would otherwise *remove* stock).
5. **List and replay:** `scripts/dlt.sh topics | list | show | replay | replay-all` (Kafka CLI inside the broker
   container). Replay re-publishes the value with its key to the original topic. Consumers dedupe by eventId, so
   replaying twice is harmless. `replay-all` uses a consumer group, so it never re-sends what it already sent.

**Test change in commit 2:** the commit-1 test checked `kafka_dlt-exception-fqcn` for "Json". That header holds
Spring's wrapper (`ListenerExecutionFailedException`), and the real error is in `kafka_dlt-exception-cause-fqcn`.
The assertion now reads the cause header. The behaviour it checks (a parse error, recorded on the DLT) is
unchanged. A third test was added (valid JSON, invalid data).

## Before / after (measured 2026-11-28, same machine, single runs)

| Case | Before (default handler) | After |
|---|---|---|
| Malformed JSON | 10 attempts, no pause, dropped (0 on DLT) | **1 attempt**, on `order.created.DLT` with the Jackson error as cause, next message on the partition processed |
| Valid JSON, total 0 | (not tested before) | 1 attempt, on the DLT with `InvalidEventException: totalAmount must be > 0`, `PaymentService.handle` never called |
| Valid event, 8 s DB outage | 10 attempts, all inside the outage, **lost** (no payment, 0 on DLT) | **3 attempts**, then on the DLT; replayed after the outage → payment created |
| Mutation: bad JSON made retryable again | n/a | 3 attempts, test fails ("bad data is not retried"); the healthy message behind it was processed after 4270 ms vs 1088 ms in the normal run |
| order-service, malformed `payment.completed` | n/a | on `payment.completed.DLT`, order stays PENDING |
| catalog-service, `order.failed` with quantity -5 | n/a | on `order.failed.DLT`, stock unchanged, event not marked processed |

`scripts/dlt.sh` was checked by hand against the local broker (2026-11-28): two seeded DLT messages were listed
(partition, offset, key, value), `show` printed the headers, `replay` re-published one with the same key (same
partition), `replay-all` sent both and then reported "Nothing new to replay" on the second run. A non-DLT topic and
a missing offset were rejected.

Commands:
```
./mvnw -pl payment-service -am verify            # "=== INCIDENT 004 ..." lines
scripts/dlt.sh topics
scripts/dlt.sh list order.created.DLT
```

## Still open
- Nobody is alerted when something lands on a DLT. Day 4: a metric/alert on DLT message counts.
- During the up-to-3 s of retries, the partition waits. A long outage turns every event into a DLT entry that must
  be replayed by hand (`replay-all`), and there's no automatic redrive yet.
