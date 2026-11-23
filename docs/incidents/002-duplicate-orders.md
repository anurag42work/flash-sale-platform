# Incident 002: Duplicate orders on client retry

| | |
|---|---|
| Status | Fixed (step 1.7) |
| Service | order-service, `POST /api/orders` |
| Branch | `incident/002-duplicate-orders` |
| Test | `order-service/src/test/java/com/flashsale/order/IdempotencyIT.java` |

## Symptom
A buyer taps "Buy" once. The request reaches the server and the order is created, but the response is
lost (mobile network drop, client timeout, a proxy retry). The client retries the same request. The
server can't tell a retry from a new purchase, so it creates a **second order** and **reserves stock
a second time**. The buyer is charged twice (once payment exists), and stock is held for an order nobody wanted.

## How it was reproduced
- Testcontainers Postgres, the real order-service over HTTP, and `FakeCatalog` for catalog-service.
- Product 1 has 10 in stock. The same `POST /api/orders {items: [{productId: 1, quantity: 2}]}` is sent
  twice, by the same user, with the same `Idempotency-Key` header (ignored by the naive code).
- Expected: 1 order, stock 8, and the retry returns the same order id.
- Command:
  ```
  ./mvnw -pl order-service -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
         -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=IdempotencyIT
  ```

## Evidence (measured 2026-11-23, before the fix)
Machine: laptop, Intel i5-8265U (4 cores / 8 threads), 8 GB RAM, Windows 11. Docker/WSL2 has 2 CPUs and 3 GB.

| Run | Status 1st / retry | Orders created | Stock left (start 10, qty 2) | Same order id? |
|---|---|---|---|---|
| 1 | 201 / 201 | 2 | 6 | no |
| 2 | 201 / 201 | 2 | 6 | no |
| 3 | 201 / 201 | 2 | 6 | no |

Unlike incident 001, this isn't a race: it happens on every retry, even a sequential one.

## Root cause
`POST` isn't idempotent. Each call runs `OrderService.create`: reserve at catalog, then insert a new
order with a new UUID. Nothing records that this request was already handled, so the server has no way to
recognise the retry. HTTP retries are normal (clients, load balancers, and the gateway's future retry
policy all do them), so a non-idempotent create endpoint will create duplicates in production.

## Fix (step 1.7, commit 2)
- **`Idempotency-Key` header required** on `POST /api/orders`: 1–64 characters of `[A-Za-z0-9_-]`
  (a UUID fits). Missing or malformed → 400 ProblemDetail.
- **`idempotency_keys` table** (Flyway `order-service` V2): `PRIMARY KEY (user_id, idem_key)`, request hash,
  status `IN_PROGRESS|COMPLETED`, stored response status + body (jsonb), `created_at` index for cleanup.
- **`IdempotencyService`** wraps `OrderService.create` (unchanged):
  1. Claim with `INSERT ... ON CONFLICT DO NOTHING`. The primary key lets exactly one request win, even
     under concurrency. The claim commits right away, so concurrent requests see it.
  2. Winner runs the order and stores the 201 response as COMPLETED.
  3. Everyone else: different request hash → **422** `IDEMPOTENCY_KEY_REUSED`. Still IN_PROGRESS →
     **409** `IDEMPOTENCY_KEY_IN_PROGRESS`. COMPLETED → the stored **201 + same body + same Location**,
     with the header `Idempotent-Replayed: true`.
  4. If the order fails (out of stock, catalog down), the key is **deleted**, so a retry with the same key
     can succeed. That's safe because the failed order already released its stock.
- **Request hash:** SHA-256 of the parsed request turned back into JSON, so whitespace or field order in
  the raw body doesn't matter.
- **Options compared** (Postgres vs Redis, claim, failure policy, transactions, scope, hash): `docs/decisions/002-idempotency.md`.
- **Cleanup:** `IdempotencyCleanup` (`@Scheduled`, hourly at :15) deletes keys older than `idempotency.ttl`
  (24h). It also clears keys stuck IN_PROGRESS after a crash.

## Before / after (same machine as above, measured 2026-11-23)
| | Before (3 runs) | After (3 runs) |
|---|---|---|
| 2 POSTs, same key: orders created | 2, 2, 2 | 1, 1, 1 |
| 2 POSTs, same key: stock left (start 10, qty 2) | 6, 6, 6 | 8, 8, 8 |
| Retry returns the same order id | no, no, no | yes, yes, yes |
| 20 concurrent POSTs, same key: orders created | not run before the fix | 1, 1, 1 |
| 20 concurrent POSTs: stock left (start 100, qty 1) | not run before the fix | 99, 99, 99 |
| 20 concurrent POSTs: 201 / 409 split | not run before the fix | 1/19, 18/2, 4/16 |

The 201/409 split varies between runs, and that's expected. Requests that arrive while the winner is still
running get 409 (retry later); requests that arrive after it finished get the stored 201 back. What
matters doesn't vary: exactly 1 order and stock reserved once.

Other tests in `IdempotencyIT`: same key + other body → 422; a request during an in-flight one → 409; a
failed order frees the key so a retry succeeds; keys are per user; missing key → 400 with nothing created;
cleanup deletes only expired keys.

## Known limits (not fixed here)
- **Crash between claim and complete:** the key stays IN_PROGRESS, so retries get 409 until cleanup
  (≤ 24h + 1h). A shorter expiry for IN_PROGRESS rows could come later.
- **Order saved but storing the response fails:** same as above (409 until cleanup), but no duplicate is created.
- **Catalog timeout after it actually reserved:** the key is deleted and the stock stays reserved. This is the
  dual-write gap; it's fixed by the outbox/saga on Day 2.
- **Retry after the TTL** creates a new order. 24h is far longer than any client retry window.

## Lessons
- Any non-idempotent endpoint that clients can retry will create duplicates. Retries are normal, not an edge case.
- Let a unique constraint decide the race (here `PRIMARY KEY (user_id, idem_key)`), not a check in Java.
- Store the response, not just "seen this key", so a retry gets exactly what the first call would have returned.
