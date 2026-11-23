# Decision 002: How to make order creation idempotent

**Status:** accepted (step 1.7, incident 002) · **Service:** order-service

## Problem
Clients retry `POST /api/orders` when a response is lost. Without protection every retry creates a new
order and reserves stock again (2 orders, stock taken twice on every run, see
`docs/incidents/002-duplicate-orders.md`). We need: a retry of the same purchase returns the first
result, a new purchase still works, and it holds under concurrent retries.

The client sends an `Idempotency-Key` header (a UUID per purchase, reused for every retry of it). The
decisions below are about what the server does with that key.

## 1. Where to store the keys

### A. Postgres, in order_db (chosen)
- **Correctness:** strongest. The key lives in the same database as the orders, which is durable and
  survives restarts. The primary key gives a hard uniqueness guarantee.
- **Cost:** one INSERT, one UPDATE, and the replay SELECT per create, all on the database we already have.
- **Complexity:** low. Plain SQL, tested with the same Testcontainers Postgres.
- **Limit:** the key and the order aren't written in one transaction (see 4), so there is a small
  window that needs the IN_PROGRESS + cleanup handling.

### B. Redis (`SET key value NX EX 86400`)
- **Correctness:** good, but weaker. Redis is configured with `allkeys-lru` here, so a key can be
  **evicted under memory pressure** before its TTL. A retry after eviction creates a duplicate. Data
  is also lost if Redis restarts without persistence.
- **Cost:** faster than Postgres, and the built-in TTL means no cleanup job.
- **Complexity:** order-service doesn't use Redis yet. It would add a dependency and a second store to
  keep consistent with orders.
- **When I'd pick it:** very high request rates where the DB is the bottleneck, with eviction disabled
  for these keys (`noeviction` or a dedicated instance) and accepting the rare duplicate.

### C. In memory (a `ConcurrentHashMap` in the service)
- Wrong as soon as there are 2 instances (each has its own map) or after a restart. Only for a demo.

## 2. How to claim a key (who wins between identical concurrent requests)

### A. `INSERT ... ON CONFLICT (user_id, idem_key) DO NOTHING` (chosen)
- One statement. Inserted rows = 1 means we won, 0 means someone else has it. The database decides;
  there's no check-then-act in Java.
- No exception used as control flow.
- **Trade-off:** Postgres-specific SQL (MySQL has `INSERT IGNORE`, others need option B).

### B. Plain INSERT, catch `DuplicateKeyException`
- Portable. But in Postgres a failed statement **aborts the surrounding transaction**, so this only
  works if the INSERT runs in its own transaction, and an exception is used for a normal outcome.

### C. `SELECT ... FOR UPDATE`, then INSERT if missing
- **Doesn't work** for the first request: you can't lock a row that doesn't exist yet. Two first
  requests both see "no row" and both continue. This is the incident 001 mistake again.

## 3. What to do when the order fails (out of stock, catalog down)

### A. Delete the key (chosen)
- A retry with the same key runs again. After a restock, or once catalog is back, it succeeds.
- Safe, because a failed create already released the stock it reserved.
- **Trade-off:** a failed request can run more than once. That's fine because failures have no
  lasting effect (except the Day 2 dual-write gap, below).

### B. Store the error response too (Stripe's approach)
- Every retry gets exactly the same error for 24h, so the result is fully deterministic.
- **Trade-off:** "out of stock" or "catalog unavailable" (503, temporary by definition) stays stuck on
  that key. The client must create a new key to try again, and many clients won't.
- **When I'd pick it:** when a failed attempt can have side effects (e.g. a payment provider that
  might have charged), so replaying the failure is safer than trying again.

## 4. Transaction boundaries

### A. Separate short statements: claim (commit), run the order, complete (commit) (chosen)
- The claim is **visible to other requests immediately**, so concurrent duplicates see IN_PROGRESS
  and get 409.
- No DB connection is held while order-service waits for catalog over HTTP (keeps the Hikari pool
  free; see the planned incident 008, pool exhaustion).
- **Trade-off:** if the service crashes between claim and complete, the key stays IN_PROGRESS and
  retries get 409 until cleanup. Handled by the 409 meaning "retry later" and the cleanup job.

### B. One transaction around claim + order + complete
- All-or-nothing in the database: a crash leaves nothing behind.
- **But:** the claim isn't visible until commit, so concurrent duplicates block on the primary key
  (or both proceed, depending on timing) while a connection is held during the catalog HTTP calls.
  It also doesn't cover catalog's side effect (the reservation is in another service and database),
  so it gives less safety than it seems.

## 5. Key scope

### A. Per user: `PRIMARY KEY (user_id, idem_key)` (chosen)
- Two users can't collide, even with weak client-generated keys, and one user can **never receive
  another user's stored response** (that would leak an order).
### B. Global key
- Simpler key, but a collision or a guessed key replays someone else's order to the wrong user.

## 6. What the request hash covers (same key + different body → 422)

### A. SHA-256 of the parsed request turned back into JSON (chosen)
- Whitespace or field order in the raw body don't matter; only the purchase content does.
- **Trade-off:** depends on Jackson output for the DTO staying stable (it's a record with fixed fields).
### B. SHA-256 of the raw body bytes
- Simplest, and independent of our DTO. But a client library that re-serialises differently on retry
  would get a false 422.

## Summary
| Question | Chosen | Main alternative | Why |
|---|---|---|---|
| Store | Postgres | Redis `SET NX EX` | Durable, no eviction risk, no new dependency |
| Claim | `INSERT ... ON CONFLICT DO NOTHING` | catch duplicate-key error | One statement, no exception as control flow |
| On failure | delete key | store error (Stripe) | Retry can succeed after restock/recovery; failures have no lasting effect |
| Transactions | short separate statements | one big transaction | Claim visible at once, no connection held during HTTP |
| Scope | per user | global | No collisions, no cross-user leak |
| Hash | parsed request as JSON | raw bytes | Formatting differences aren't a different purchase |

No performance numbers here. The measured results are in incident 002 (correctness only: 1 order
and stock reserved once, on 3 of 3 runs, including 20 concurrent requests).

## Not solved here
- **Crash between claim and complete:** 409 until cleanup (up to about 25h). Possible later: treat
  IN_PROGRESS rows older than a few minutes as abandoned.
- **Catalog timeout after it actually reserved:** the key is deleted but the stock stays reserved.
  That's the dual-write problem, which the outbox and saga fix on Day 2 (steps 2.1–2.3).
- The same idea comes back for Kafka consumers on Day 2 (`processed_events`: an idempotent receiver).
