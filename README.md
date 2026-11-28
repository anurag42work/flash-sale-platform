# Flash-Sale Order Platform

Spring Boot microservices project: a flash-sale order system built to reproduce and fix real
production problems (overselling, duplicate orders, dual writes, slow queries, cascading failures).
Every fix is proven with a test, with before/after numbers.

Work in progress. Design: [docs/design.md](docs/design.md). Open items: [docs/todo.md](docs/todo.md).

## Modules

| Module | Port | Database | Role |
|---|---|---|---|
| gateway-service | 8080 | none | Single entry point: JWT check (JWKS), routing, identity headers, correlation id |
| auth-service | 8081 | auth_db | Register/login, RS256 access tokens, rotating refresh tokens, JWKS |
| catalog-service | 8082 | catalog_db | Products and stock (atomic reservation) |
| order-service | 8083 | order_db | Orders, Idempotency-Key, reserves stock at catalog |
| payment-service | 8084 | payment_db | Skeleton (Day 2) |
| common-lib | none | none | Shared header names and the page response |

Stack: Java 21, Spring Boot 3.5, Spring Cloud 2025.0, PostgreSQL 16 + Flyway, Redis 7, Kafka 3.9 (KRaft).

## Incidents reproduced and fixed

| # | Problem | Proof | Fix | Write-up |
|---|---|---|---|---|
| 001 | Overselling under concurrent reservations | 200 buyers, 50 in stock → 200 sold | Atomic conditional `UPDATE ... WHERE available >= :qty`, `CHECK (available >= 0)` | [incident](docs/incidents/001-overselling.md), [decision](docs/decisions/001-stock-concurrency.md) |
| 002 | Duplicate orders on client retry | Same request twice → 2 orders, stock taken twice | Required `Idempotency-Key`, stored response replayed | [incident](docs/incidents/002-duplicate-orders.md), [decision](docs/decisions/002-idempotency.md) |
| 003 | Dual write: order saved, event lost | Kafka send fails after the DB commit → order PENDING, 0 events | Transactional outbox: order + event row in one transaction, `SKIP LOCKED` relay publishes (Kafka paused → all 5 events delivered 690 ms after unpause) | [incident](docs/incidents/003-dual-write.md), [decision](docs/decisions/003-dual-write.md) |

## Run locally

Requirements: JDK 21, Docker. Maven isn't needed (the `./mvnw` wrapper downloads it).
On Windows, run these in Git Bash (in PowerShell, use `.\mvnw.cmd` instead of `./mvnw`).

```bash
# 1. Once: secrets and JWT signing keys (.env and keys/ are git-ignored)
cp .env.example .env                 # then change every password
scripts/gen-dev-keys.sh

# 2. Infrastructure: Postgres, Redis, Kafka (about 1.4 GB of memory in total)
docker compose -f deploy/compose.infra.yml up -d
docker compose -f deploy/compose.infra.yml ps        # wait until all are "healthy"

# 3. One service per terminal, with a small JVM (-Xmx256m)
scripts/run-local.sh catalog
curl http://localhost:8082/actuator/health            # {"status":"UP",...}
```

`run-local.sh` loads `.env`, uses the `dev` profile (catalog seeds **10,000 products with 100 units each**
on an empty database) and listens on **127.0.0.1 only**. catalog and order trust the identity headers set
by the gateway, and `/internal/**` has no auth, so those services must never be reachable from the network.

Each JVM uses about 200–250 MB. On a small laptop, run 1–2 services at a time for everyday work.
The full flow through the gateway (auth, catalog, order, gateway) also runs on an 8 GB laptop if other
apps are closed (4 JVMs used about 190–245 MB each). Start them one at a time and wait until each
`/actuator/health` is `UP` before starting the next, so only one Maven build runs at a time.

The Postgres init script runs only when the data volume is empty. If you change passwords in `.env`
later, recreate the volume: `docker compose -f deploy/compose.infra.yml down -v`.

## End-to-end flow through the gateway

Clients only talk to the gateway (port 8080). Start auth, catalog, order and gateway (one terminal each), then
(checked end to end on the laptop on 2026-11-23):

```bash
# Register and log in
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}'                 # 201
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')

# Browse products (public)
curl -s 'localhost:8080/api/products?page=0&size=5&sort=price,desc'
curl -s localhost:8080/api/products/42                                        # includes "available"

# Order: one new Idempotency-Key per purchase, reused on every retry of that purchase
KEY=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || powershell -c "[guid]::NewGuid()")
curl -si -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"items":[{"productId":42,"quantity":3},{"productId":43,"quantity":1}]}'   # 202 Accepted, PENDING (publishes OrderCreated to Kafka)
# Send the same command again: same order id, header "Idempotent-Replayed: true", stock not taken twice.

curl -s localhost:8080/api/orders -H "Authorization: Bearer $TOKEN"           # own orders, newest first
```

Without the gateway (e.g. only catalog + order running), call the service port directly and send the
identity header yourself: `-H 'X-User-Id: 11111111-2222-3333-4444-555555555555'` (port 8083 for orders).

## API

All errors are RFC 7807 `application/problem+json`: `400` lists invalid fields under `errors`, and an
unexpected `500` never contains internal details.

### Gateway (8080)

| Path | Routed to | Token needed |
|---|---|---|
| `/api/auth/**` | auth-service | no |
| `GET /api/products/**` | catalog-service | no |
| other `/api/products/**` | catalog-service | yes (ADMIN checked by catalog) |
| `/api/orders/**` | order-service | yes |
| anything else (including `/internal/**`) | nothing | denied (401) |

The gateway checks the JWT with auth-service's public key (signature, expiry, issuer), removes any
`X-User-Id` / `X-User-Roles` the client sent, sets them from the token, and adds `X-Correlation-Id`.
Paths with traversal tricks (`..`, `%2e`, `%2f`, `;`) are rejected with 400.

### auth-service

| Endpoint | Result |
|---|---|
| `POST /api/auth/register` | 201 user; 400 invalid input (password 8–72 characters and at most 72 bytes); 409 email taken |
| `POST /api/auth/login` | 200 `accessToken` (RS256 JWT, 15 min) + `refreshToken`; 401 wrong email or password |
| `POST /api/auth/refresh` | 200 new token pair (rotation); 401 invalid/expired/reused token (reuse revokes the session) |
| `POST /api/auth/logout` | 204, the session's refresh tokens are revoked |
| `GET /.well-known/jwks.json` | public signing key (JWKS) |

### catalog-service

| Endpoint | Result |
|---|---|
| `GET /api/products` | public; `page`, `size` (max 100), `sort` = `id`/`name`/`price` + `,asc`/`,desc` |
| `GET /api/products/{id}` | public, with current stock; 404 if missing or inactive |
| `POST /api/products`, `PUT /api/products/{id}` | ADMIN only; 401 without user, 403 without role, 409 duplicate SKU |
| `POST /internal/inventory/{id}/reserve` | service-to-service; atomic, 409 `OUT_OF_STOCK` when not enough |
| `POST /internal/inventory/{id}/release` | service-to-service; gives stock back |

### order-service

| Endpoint | Result |
|---|---|
| `POST /api/orders` | **`Idempotency-Key` required** (1–64 of `A-Z a-z 0-9 - _`). 202 Accepted: PENDING order with price snapshot (an `OrderCreated` event goes to Kafka topic `order.created`). Retry with the same key → same 202 + `Idempotent-Replayed: true`. 400 missing key, 409 `OUT_OF_STOCK`, 409 `IDEMPOTENCY_KEY_IN_PROGRESS`, 422 unknown product, 422 `IDEMPOTENCY_KEY_REUSED` (same key, other body), 503 catalog down or slow |
| `GET /api/orders/{id}` | own order only (someone else's → 404) |
| `GET /api/orders` | own orders, newest first, paged |

## Test

```bash
./mvnw -pl order-service -am verify     # one module: unit tests + Testcontainers ITs
./mvnw clean verify                     # everything (CI, or when the laptop is free)

# One integration test class only (e.g. the overselling proof):
./mvnw -pl catalog-service -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=InventoryConcurrencyIT
```
In PowerShell, put each `-D...` argument in quotes.

- `*Test` classes run with Surefire (no Docker).
- `*IT` classes run with Failsafe and start a real Postgres via Testcontainers, so Docker must be running.
- Optional, for faster local runs: add `testcontainers.reuse.enable=true` to `~/.testcontainers.properties`
  to keep the test Postgres container between runs.
