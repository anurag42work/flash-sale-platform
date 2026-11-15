# Flash-Sale Order Platform

Spring Boot microservices project: a flash-sale order system built to reproduce and fix real
production problems (overselling, duplicate orders, slow queries, cascading failures).

Work in progress. Design: [docs/design.md](docs/design.md).

## Modules

| Module | Port | Database |
|---|---|---|
| gateway-service | 8080 | none |
| auth-service | 8081 | auth_db |
| catalog-service | 8082 | catalog_db |
| order-service | 8083 | order_db |
| payment-service | 8084 | payment_db |
| common-lib | none | shared constants |

Stack: Java 21, Spring Boot 3.5, Spring Cloud 2025.0, PostgreSQL 16 + Flyway, Redis 7, Kafka 3.9 (KRaft).

## Run locally

Requirements: JDK 21, Docker. Maven is not needed (the `./mvnw` wrapper downloads it).

```bash
# 1. Secrets (once): copy the template, then change every password
cp .env.example .env

# 2. Infrastructure: Postgres, Redis, Kafka (about 1.4 GB of memory in total)
docker compose -f deploy/compose.infra.yml up -d
docker compose -f deploy/compose.infra.yml ps        # wait until all are "healthy"

# 3. One service at a time, with a small JVM
scripts/run-local.sh catalog
curl http://localhost:8082/actuator/health            # {"status":"UP",...}
```

### Try the auth service

```bash
scripts/gen-dev-keys.sh          # once: RSA key pair for signing JWTs (keys/ is git-ignored)
scripts/run-local.sh auth        # port 8081

curl -i -X POST localhost:8081/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}'            # 201
curl -s -X POST localhost:8081/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}'            # accessToken + refreshToken
curl -s -X POST localhost:8081/api/auth/refresh -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refreshToken from login>"}'                      # new pair; the old token is now revoked
curl -s localhost:8081/.well-known/jwks.json                             # public key used by the gateway
```

| Endpoint | Result |
|---|---|
| `POST /api/auth/register` | 201 user, 400 invalid input, 409 email taken |
| `POST /api/auth/login` | 200 tokens (access token: RS256 JWT, 15 min), 401 wrong email or password |
| `POST /api/auth/refresh` | 200 new token pair (rotation), 401 invalid/expired/reused token; reuse revokes the whole session |
| `POST /api/auth/logout` | 204, the session's refresh tokens are revoked |
| `GET /.well-known/jwks.json` | public signing key (JWKS) |

Errors are RFC 7807 `application/problem+json`.

### Through the gateway (port 8080)

Clients only talk to the gateway. Run auth and gateway together (two terminals):

```bash
scripts/run-local.sh auth
scripts/run-local.sh gateway

curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}'            # same as auth, via the gateway
curl -i localhost:8080/api/orders/1                                      # 401 problem+json, no token
curl -i localhost:8080/api/orders/1 -H "Authorization: Bearer <accessToken>"
```

| Path | Routed to | Token needed |
|---|---|---|
| `/api/auth/**` | auth-service | no |
| `GET /api/products/**` | catalog-service | no |
| other `/api/products/**` | catalog-service | yes |
| `/api/orders/**` | order-service | yes |
| anything else | nothing | denied (401) |

The gateway checks the JWT with auth-service's public key (JWKS: signature, expiry, issuer), removes any
`X-User-Id` / `X-User-Roles` the client sent, sets them from the token, and adds `X-Correlation-Id`
(kept from the request if it is a safe value, otherwise generated). Downstream addresses are set with
`AUTH_URL`, `CATALOG_URL`, `ORDER_URL` and `AUTH_JWKS_URI`.

The Postgres init script runs only when the data volume is empty. If you change passwords in
`.env` later, recreate the volume: `docker compose -f deploy/compose.infra.yml down -v`.

On Windows, run the commands in Git Bash (PowerShell: use `.\mvnw.cmd` instead of `./mvnw`).

## Test

```bash
./mvnw -pl catalog-service -am verify   # one module (unit tests + Testcontainers ITs)
./mvnw clean verify                     # everything
```

- `*Test` classes run with Surefire (no Docker).
- `*IT` classes run with Failsafe and start a real Postgres via Testcontainers, so Docker must be running.
- Optional, faster local runs: add `testcontainers.reuse.enable=true` to `~/.testcontainers.properties`
  to keep the test Postgres container between runs.
