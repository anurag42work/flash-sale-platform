# Incident 003: Dual write, order saved but its event is lost

| | |
|---|---|
| Status | Reproduced (step 2.1). Fix: transactional outbox (step 2.2) |
| Service | order-service, `POST /api/orders` → Kafka topic `order.created` |
| Branch | `incident/003-dual-write` |
| Test | `order-service/src/test/java/com/flashsale/order/OrderEventsIT.java` |

## Symptom
A buyer places an order and gets `202 Accepted`. The order is in the database as `PENDING`, but no
`OrderCreated` event ever reaches Kafka. Payment (step 2.3) reacts to that event, so the order stays
`PENDING` forever. Its stock is still reserved at catalog. Nothing in the system knows anything is missing.
The only trace is one ERROR log line.

## How it was reproduced
- Testcontainers Postgres 16 and Kafka (`apache/kafka:3.9.1`, KRaft), the real HTTP endpoint, and a fake catalog.
- Create-order flow (naive, `OrderService.create` + `OrderEventPublisher`):
  1. reserve stock at catalog
  2. `orders.save(order)`: **commit 1**, Postgres
  3. `kafkaTemplate.send("order.created", orderId, json)`: **write 2**, Kafka (fire and forget, failure logged)
  4. return `202 Accepted`
- The test wraps the `KafkaTemplate` in a Mockito spy. The **first** `send` throws
  `KafkaException("simulated: broker unreachable")`, and later sends work. That is the window after the DB commit
  and before the Kafka write succeeds: Kafka down, a network blip, a timeout, or the JVM killed right after the
  commit.
- Expected (what the test asserts): the committed order gets exactly one `OrderCreated` event within 15 s.
- Command:
  ```
  ./mvnw -pl order-service -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
         -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=OrderEventsIT
  ```
- The other test in the class (no failure injected) shows the happy path: one event, keyed by orderId.

## Evidence (measured 2026-11-28, before the fix)
Machine: laptop, Intel i5-8265U (4 cores / 8 threads), 8 GB RAM, Windows 11. Docker/WSL2 has 2 CPUs and 3 GB.

```
=== INCIDENT 003: HTTP 202, order 5dcc65e4-… in DB as PENDING, OrderCreated events after 15 s: 0 (expected 1) ===
ERROR c.f.order.order.OrderEventPublisher : OrderCreated for order 5dcc65e4-… was not sent (lost)
```

| | Client saw | Order row | Events on `order.created` | Stock at catalog |
|---|---|---|---|---|
| Expected | 202 | PENDING | 1 | reserved |
| Actual | 202 | PENDING | **0** | reserved, never released |

The failure is injected, so the result is the same on every run. The test fails at
`[a committed order must get exactly one OrderCreated event] Expected size: 1 but was: 0`.

## Root cause: two writes, two systems, no shared transaction
Postgres and Kafka can't commit together. Whichever order you write them in, a crash or error between the two
leaves them out of sync:

| Order of writes | Failure between them | Result |
|---|---|---|
| DB commit, then Kafka (our code) | Kafka down / timeout / process dies | order exists, **event lost** (this incident) |
| Kafka, then DB commit | DB commit fails / process dies | event for an order that **does not exist** (payment charges a ghost order) |
| Kafka inside the DB transaction (`@Transactional` around both) | DB commit fails after the send | same as above: Kafka doesn't roll back with the DB |

Retrying inline doesn't fix it either. A retry loop in the request thread still loses the event if the process
dies, and while Kafka is down it blocks the HTTP thread (`max.block.ms`, 5 s in our config, 60 s by default).

Surfacing the error to the client (500) instead of logging it is worse. The order is already committed and the
stock reserved. Incident 002's idempotency layer deletes the key on failure, so the client's retry creates a
**second** order, and the first one is still stuck.

## The 3 common solutions
1. **Transactional outbox (polling relay)** — write the order and an `outbox` row in the **same** DB transaction.
   A separate relay reads unsent rows, publishes them to Kafka, and marks them sent. If Kafka is down, the rows
   wait and are retried. Delivery is at-least-once, so consumers must be idempotent (eventId).
   Cost: one extra table, publish delay of up to one poll interval, and polling load on the DB.
2. **Change data capture (CDC, e.g. Debezium)** — still write an outbox (or the order) row in one transaction,
   but instead of polling, Debezium tails the Postgres WAL and streams the changes to Kafka. Lower latency and no
   polling queries. Cost: it needs Kafka Connect plus Debezium (another always-on JVM), a logical replication
   slot (an unread slot makes Postgres keep WAL and fill the disk), and more parts to run and monitor. Too much
   for our small shared VM.
3. **Event first / listen to yourself** — write only to Kafka. Order-service consumes its own topic and writes the
   DB from it (Kafka is the source of truth). Cost: a big redesign. The client can't read its own order right
   after the 202 (eventual consistency even for the writer), and Kafka must be up to accept any order.

(Two-phase commit / XA across Postgres and Kafka isn't an option: Kafka doesn't take part in XA transactions.)

## Fix
Step 2.2: transactional outbox with a `SELECT ... FOR UPDATE SKIP LOCKED` polling relay (options compared in
`docs/decisions/003-dual-write.md`). The same
`OrderEventsIT` test must pass unchanged after it.

## Before / after
| | Committed orders with their event (injected first-send failure) |
|---|---|
| Before (naive, 2026-11-28) | 0 of 1 within 15 s |
| After (outbox) | to be measured in step 2.2 |
