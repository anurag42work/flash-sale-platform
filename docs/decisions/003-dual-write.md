# Decision 003: Getting the OrderCreated event out reliably (dual write)

**Status:** accepted (step 2.1 reproduced, step 2.2 built, incident 003) · **Service:** order-service

## Problem
Creating an order writes to Postgres (the order row) and to Kafka (the `OrderCreated` event that drives payment).
The two systems can't commit together. In step 2.1 the naive code commits the order and then sends the event.
When the send fails, the order is stuck `PENDING` with no event (`docs/incidents/003-dual-write.md`: 0 of 1
events delivered). We need every committed order to get its event, and no event for an order that was never
committed.

## 1. How to make the two writes consistent

### A. Transactional outbox + polling relay (chosen)
- **Correctness:** order row and outbox row commit (or roll back) together, so there's never an order without
  an event or an event without an order. Delivery is at-least-once (the relay can publish and then crash before
  marking the row sent), so consumers dedupe by eventId.
- **Cost:** one INSERT per order, plus a poll query every interval (cheap with a partial index on unsent rows).
  Events arrive up to one poll interval late.
- **Complexity:** low. A table, a `@Scheduled` method, plain SQL. `FOR UPDATE SKIP LOCKED` lets several
  instances poll safely.

### B. Outbox + CDC (Debezium reading the Postgres WAL)
- **Correctness:** same as A (the outbox row is still written in the order's transaction).
- **Cost:** no polling queries and lower latency, but Kafka Connect + Debezium is another always-on JVM, and the
  logical replication slot makes Postgres keep WAL until it's read (a stopped connector can fill the disk).
- **Complexity:** high: connector config, slot monitoring, one more service to deploy and upgrade.

### C. Event first / listen to yourself
- **Correctness:** Kafka is the source of truth and the DB is built from the topic, so there is only one write.
- **Cost:** the client can't read its own order right after the 202. Kafka must be up to accept any order.
- **Complexity:** a redesign of order-service (consumer-driven writes, replay, idempotent projection).

### Not an option: 2PC / XA
Kafka doesn't take part in XA transactions. Kafka transactions only cover Kafka (and Kafka-to-Kafka
consume-process-produce), not a Postgres commit.

**When I'd pick each:** A by default, on a small VM, or when a delay of one poll interval is fine. B at high
event volume or when the polling load or latency starts to matter, and someone runs Kafka Connect anyway.
C when the system is event-sourced by design.

## 2. Choices in the naive version (step 2.1)

### Failure policy for the send: log and swallow (chosen for the demo) vs fail the request (500)
- **Log and swallow:** the client gets 202 for an order whose event is lost. This is silent data loss, and it's
  the bug we demonstrate.
- **Fail with 500:** looks safer but is worse. The order is already committed and the stock reserved. The
  idempotency layer (decision 002) deletes the key on failure, so the client's retry creates a **second** order
  and the first stays stuck.
- Neither is a fix, which is why the outbox moves the send out of the request entirely.

### Response status: 202 Accepted (chosen) vs 201 Created
- 202 says "accepted for processing": the order is `PENDING` until payment (async, step 2.3) decides.
  The body (id, status) and `Location` stay the same, so a client can poll `GET /api/orders/{id}`.
- 201 is still technically true (the row exists), but it suggests the work is finished.
- Trade-off: clients written for Day 1 that check for exactly 201 must change. There are none outside the tests.

### How the test injects the failure: Mockito spy on KafkaTemplate (chosen) vs stopping the Kafka container
- **Spy:** the first `send()` throws and later ones work. It's deterministic, fast, and the same test proves the
  fix (the outbox relay retries through the real template).
- **Stopping the container:** more realistic, but a restarted Testcontainer gets a new port, so the app's
  bootstrap servers point at nothing. Step 2.2's "Kafka down, then back" test will use `docker pause` instead.

## 3. How the outbox is built (step 2.2)

### Transaction boundary: `TransactionTemplate` inside `create` (chosen) vs `@Transactional` on another bean
- The catalog HTTP calls must stay **outside** the transaction (no DB connection held while waiting on the
  network). With `TransactionTemplate` the boundary is visible in one method: reserve → `tx.execute { save order;
  add outbox row }` → on failure, release.
- `@Transactional` on a method of the same class doesn't work (self-invocation skips the proxy), so it would
  need an extra bean just for the two inserts. Trade-off: a lambda instead of an annotation.

### Outbox insert: JdbcClient (chosen) vs a JPA entity
- One INSERT with `CAST(:payload AS jsonb)`, the same style as the other atomic SQL (decision 13). It joins the
  JPA transaction because `JpaTransactionManager` exposes the same connection to JDBC.
- A JPA entity would need a jsonb type mapping and gives no benefit for an append-only table.

### "Must be in a transaction" check: explicit `TransactionSynchronizationManager` check (chosen) vs `@Transactional(MANDATORY)`
- Same behaviour (throw if no transaction). Without a proxy, the bean can be spied in tests: a Mockito spy
  behind a transactional proxy rejected the stubbing call, because the stubbing itself ran outside a transaction.

### Relay: hold the row locks while waiting for Kafka (chosen) vs claim rows with a lease column
- **Chosen:** one transaction per batch: `FOR UPDATE SKIP LOCKED`, send + wait for the ack, mark sent, commit.
  It's simple and correct: a row can't be picked by another relay while it's being sent.
  Cost: one DB connection (of 10) is held per run, up to ~`delivery.timeout.ms` (10 s) per row when Kafka is
  slow or down, and only one relay thread runs per instance.
- **Lease:** `UPDATE ... SET locked_until = now() + 30s ... RETURNING` (claim), send without a transaction,
  then mark sent. No connection is held during the send. Cost: an extra column, and a lease that expires
  mid-send gives a duplicate.

### On failure: stop the batch (chosen) vs skip the row and continue
- Stopping keeps per-order ordering (OrderCreated before a later OrderCancelled, for example).
  Cost: one row that can never be sent blocks all later ones (head-of-line blocking). That needs a
  max-attempts / park rule, which is on the todo list.
- Skipping keeps other events flowing but can reorder events of the same order.

### Topic stored per row (chosen) vs mapping event_type → topic in code
- A `topic` column (one more than design.md first planned) means the relay needs no switch statement, and a
  new event type needs no relay change. Cost: the topic name is repeated in every row.

### Polling interval 500 ms (configurable)
- Bounds the extra delay before an event is published. Each poll is one indexed query on a partial index
  (only unsent rows), so the idle cost is small. Lower means faster events but more queries.

### Why polling and not Debezium here
- No extra JVM or Kafka Connect cluster, no replication slot to watch, nothing new to deploy on the small VM,
  and it's all plain SQL I can explain and test. Revisit if event volume makes polling load or latency matter.
