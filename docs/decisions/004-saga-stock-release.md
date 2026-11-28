# Decision 004: Payment saga, idempotent consumers and how stock comes back

**Status:** accepted (step 2.3) · **Services:** order-service, payment-service, catalog-service

## Problem
After step 2.2 an order is PENDING with its stock reserved, and an `OrderCreated` event reliably reaches Kafka.
Payment has to happen next, and the order has to end CONFIRMED or FAILED. A FAILED order must give its stock back.
Kafka delivers at-least-once, so every step must cope with the same event arriving twice.

```
order-service --order.created--> payment-service --payment.completed|failed--> order-service
                                                                  (FAILED) --order.failed--> catalog-service
```

## 1. How a FAILED order gives its stock back

### A. Publish `order.failed` through order-service's outbox; catalog consumes it (chosen)
- **Correctness:** the status change and the outbox row commit together, so "FAILED" and "stock must come back"
  can't drift apart. Catalog records the eventId in `processed_events` in the same transaction as the release,
  so a redelivery is a no-op. Proven by mutation: without that check, the same event twice turned 8 + 2 units
  into 12 (2 units created out of nothing).
- **Cost:** catalog-service needs Kafka (a consumer and one more table). Stock comes back a moment later
  (outbox poll + consumer), not within the request.
- **Complexity:** the same three patterns as the rest of the saga (outbox, processed_events, conditional update).

### B. order-service calls `POST /internal/inventory/{id}/release` (HTTP)
- **Correctness:** this is the dual write again (incident 003). Commit FAILED, then call: a crash or a catalog
  timeout in between leaks the stock. Call first, then commit: if the commit fails, Kafka redelivers the payment
  event and release runs twice. **Release adds stock, so it isn't idempotent**, and a double release creates stock.
  Making it safe needs a release-id table in catalog anyway, which is option A's table.
- **Cost:** immediate, and no Kafka in catalog. But catalog being down blocks the payment consumer (retries).
- **Complexity:** looks smaller, but the failure handling isn't.

### Event name: `order.failed` (a fact, chosen) vs `stock.release` (a command)
- A fact says what happened. Catalog decides what to do (release), and others can react too (notifications,
  analytics) without order-service knowing about them.
- A command names its receiver and its action, which couples order-service to catalog's API. It's fine when only
  one service will ever act on it.

**When I'd pick B:** a single database (then it's just one transaction), or a release endpoint that's idempotent
by design (keyed by orderId).

## 2. Idempotent consumers (all three services)
Every consumer: parse the envelope, then in **one** DB transaction run
`INSERT INTO processed_events ... ON CONFLICT DO NOTHING`. 0 rows means a duplicate, so stop. Otherwise do the
business change (and the outbox row), commit, and only then ack the Kafka offset (`ack-mode: manual_immediate`,
auto-commit off).

- **Why ack after the commit:** committing the offset first (auto-commit) can lose an event when the process
  dies before processing it (at-most-once). Acking after the commit can deliver twice, but never zero times
  (at-least-once). The dedupe table turns "twice" into "once".
- **Alternative:** Kafka exactly-once (transactions). That only covers Kafka → Kafka. Our side effects are DB
  writes (and an external payment), so it doesn't apply.

## 3. Payment-specific choices

### Provider call outside the DB transaction, with idempotency key = orderId (chosen)
- No DB connection is held while waiting for the provider (latency is configurable, and incident 005 makes it
  slow). A duplicate event that gets past the `processed_events` read can reach the provider again, so the
  provider must be idempotent: the mock returns the first result per orderId, like Stripe's `Idempotency-Key`.
- **Alternative:** call it inside the transaction. Simpler, but 10 slow payments = an empty connection pool.

### Three duplicate guards
1. A read of `processed_events` before charging (the normal redelivery).
2. The provider's idempotency key (no second charge).
3. In the transaction: `processed_events` PK (two copies at once) and `payments UNIQUE (order_id)`
   (same order, different eventId).

### The outbox code is copied into payment-service (chosen) vs moved to common-lib
- Each service owns its outbox table and can change it on its own. CLAUDE.md keeps common-lib tiny.
  Cost: about 120 duplicated lines. If a third service needs to publish, move it to common-lib.

## 4. Order status changes: conditional UPDATE (chosen) vs `@Version`
- `UPDATE orders SET status = ... WHERE id = ? AND status = 'PENDING'`: one statement, and only legal transitions
  happen. A late `PaymentCompleted` for a FAILED order updates 0 rows. An `OrderFailed` is published only when the
  transition really happened, so stock is released once.
- `@Version` + load/modify/save: more code, plus an `OptimisticLockException` to retry. The rule "only from
  PENDING" would still need an `if` in Java.

## 5. Test isolation with a shared broker
All Spring test contexts in one JVM share one Kafka container. A cached context keeps consuming after its class
finished. With the same group, Kafka split partitions between contexts (events landed in another test's DB). With
different groups, both contexts processed the event and both published results. Rule: a random group id per test
context (`src/test/resources/application.properties`) **and** `@DirtiesContext` on every IT class.

## Not covered yet
- A message that always fails (bad JSON, a bug) is retried about 10 times by Spring Kafka's default error
  handler and then **skipped with only a log line**. DLQ: step 2.4 (incident 004).
- A crash between reserving at catalog and committing the order still leaks stock. Reconciliation: step 2.6.
- An order whose payment event never comes stays PENDING. Stuck-order job: step 2.6.
