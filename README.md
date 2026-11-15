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
