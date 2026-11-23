# Incident 002: Duplicate orders on client retry

| | |
|---|---|
| Status | Reproduced (step 1.7, commit 1). Fix: step 1.7, commit 2 |
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

## Fix
To be added in commit 2: an `Idempotency-Key` header (required), an `idempotency_keys` table with
`PRIMARY KEY (user_id, idem_key)`, and stored responses replayed on retry.
