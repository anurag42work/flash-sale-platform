# Decision 005: What a consumer does with a message it can't process

**Status:** accepted (step 2.4, incident 004) · **Services:** payment, order, catalog (every Kafka consumer)
(Numbered 005 because 004 was already the saga decision. It belongs to incident 004.)

## Problem
Spring Kafka's default error handler tries a failing record 10 times with no pause, then logs and skips it.
Measured in `docs/incidents/004-poison-message.md`: malformed JSON and a valid event during an 8 s DB outage were
both **dropped**. We need: bad data parked at once, transient failures retried a little and then parked, nothing
lost, and a way to replay.

## 1. Retry style

### A. Blocking retries in the consumer (`DefaultErrorHandler` + backoff) (chosen)
- **Ordering:** the partition waits until the record succeeds or is parked, so events of one order (same key, same
  partition) are never reordered.
- **Cost:** while one record is retried (up to ~3 s here), the other records on that partition wait.
- **Complexity:** one bean, no extra topics besides the DLT.

### B. Non-blocking retry topics (`@RetryableTopic`: `topic-retry-1000`, `topic-retry-2000`, ..., `topic-dlt`)
- **Ordering:** a failed record moves to a retry topic and the partition continues, so a later event of the same
  order can be processed **before** the earlier one (e.g. PaymentFailed before OrderCreated is settled).
- **Cost:** no waiting on the main partition, but 2–3 extra topics per topic and extra consumers.
- **When I'd pick it:** high throughput where order per key doesn't matter, or long retry delays (minutes).

## 2. Which errors are retried
- **Never retried:** bad data, meaning Jackson parse errors and `InvalidEventException` (missing ids, wrong type,
  amount ≤ 0, quantity ≤ 0). Retrying can't fix it, and every retry delays the healthy records behind it.
  Measured by mutation: making bad JSON retryable again cost the next message about 3 s more.
- **Retried (3 attempts, 1 s then 2 s):** everything else (DB errors, timeouts, bugs we don't know yet). This rides
  out a short blip. Anything longer is parked and replayed after the fix.
- **Alternative:** a long or infinite retry for transient errors. That's how "retry forever and block the
  partition" happens: one stuck record stops every order behind it. Parking and replaying keeps the system
  moving, at the cost of a manual step.

## 3. Where failed records go: `<topic>.DLT`, same partition number (chosen)
- Keeps the key and the partition, so a replay goes to the same partition of the original topic. The headers keep
  the error and the original offset for diagnosis.
- The name is explicit (`record.topic() + ".DLT"`). Spring Kafka 3.x would default to `<topic>-dlt`.
- Each DLT is declared by the service that consumes the original topic, with the same partition count. A DLT
  with fewer partitions would make the "same partition" publish fail.
- **Alternative:** a DB table of failed messages. It's easier to query, but it's a second store to keep in sync,
  and replay would need custom code.

## 4. Shared setup in common-lib, with `spring-kafka` optional (chosen) vs a copy per service
- All three consuming services need the same policy, so this is the "third user" rule from decision 004.
  `optional` means common-lib doesn't force spring-kafka on its users (each consumer already has it).
- Jackson stays out of common-lib: each service passes `JsonProcessingException.class` as an extra
  non-retryable type. That's one argument per service. If one forgets, its "bad JSON" test fails.
- **Alternative:** a 30-line config copied three times, which is easy to let drift apart.

## 5. List and replay: `scripts/dlt.sh` (chosen) vs an ADMIN endpoint
- **Script:** the Kafka CLI tools inside the broker container (laptop and VM). There's no new HTTP endpoint, no
  gateway route and no admin attack surface. Cost: it's checked by hand (recorded in the incident doc) instead of
  by an automated test, and it can't replay values that contain a newline (ours are single-line JSON).
- **Endpoint:** covered by ITs and usable without shell access, but it needs a gateway route, an ADMIN check, and
  code that reads arbitrary topics.
- Replay is safe to repeat because every consumer dedupes by eventId (decision 004).
