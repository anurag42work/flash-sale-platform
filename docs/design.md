# Initial design (approved 2026-11-15, step 1.0)

Source of truth for module layout, schemas and Day 1 dependencies.
Changes after this point go through a Flyway migration and a note here.

## 1. Modules and ports

```
flash-sale-platform/            parent pom (packaging pom), groupId com.flashsale
├── common-lib/                 plain jar, no Boot plugin
├── gateway-service/   :8080    only externally exposed port (later behind Nginx)
├── auth-service/      :8081
├── catalog-service/   :8082
├── order-service/     :8083
└── payment-service/   :8084
deploy/compose.infra.yml        postgres:16.16 :5432 · redis:7.4 :6379 · apache/kafka:3.9.1 (KRaft) :9092
```

Package by feature inside each service, e.g. `com.flashsale.order.{order,idempotency,outbox,config}`.

## 2. Database schemas

Conventions: all timestamps are `timestamptz NOT NULL DEFAULT now()`. One database per service,
one Postgres role per database. No cross-service FKs (different databases); FKs only inside a DB.
"Flyway" = the step that introduces the change, so each migration belongs to its feature or incident.

### auth_db

| Table | Columns / constraints | Indexes | Flyway |
|---|---|---|---|
| users | `id uuid PK`, `email varchar(254) NOT NULL UNIQUE` (normalised to lower case in the app), `password_hash varchar(100) NOT NULL`, `role varchar(16) NOT NULL CHECK (role IN ('USER','ADMIN'))`, `created_at` | unique(email) | V1 |
| refresh_tokens | `id uuid PK`, `user_id uuid NOT NULL FK→users ON DELETE CASCADE`, `token_hash char(64) NOT NULL UNIQUE` (SHA-256 hex), `family_id uuid NOT NULL`, `expires_at timestamptz NOT NULL`, `revoked_at timestamptz NULL`, `replaced_by uuid NULL FK→refresh_tokens`, `created_at`, `CHECK (expires_at > created_at)` | unique(token_hash), (user_id), (family_id) | V1 |

### catalog_db

| Table | Columns / constraints | Indexes | Flyway |
|---|---|---|---|
| products | `id bigint GENERATED ALWAYS AS IDENTITY PK`, `sku varchar(64) NOT NULL UNIQUE`, `name varchar(200) NOT NULL`, `description text`, `price numeric(12,2) NOT NULL CHECK (price > 0)`, `active boolean NOT NULL DEFAULT true`, `created_at`, `updated_at` | unique(sku) | V1 |
| inventory | `product_id bigint PK FK→products ON DELETE RESTRICT`, `available int NOT NULL`, `updated_at` | PK | V1; `CHECK (available >= 0)` in V2 (fix for incident 001, step 1.6) |
| processed_events | same as order_db (idempotent `order.failed` consumer) | PK | V3 (step 2.3) |

Inventory is a separate 1:1 table so the hot, frequently updated stock row is isolated from the
product row that is read and cached.

### order_db

| Table | Columns / constraints | Indexes | Flyway |
|---|---|---|---|
| orders | `id uuid PK`, `user_id uuid NOT NULL`, `status varchar(16) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','FAILED'))`, `total_amount numeric(12,2) NOT NULL CHECK (total_amount >= 0)`, `failure_reason varchar(64) NULL`, `created_at`, `updated_at` | none at V1 (see "Deliberate omissions") | V1 |
| order_items | `id bigint GENERATED ALWAYS AS IDENTITY PK`, `order_id uuid NOT NULL FK→orders ON DELETE CASCADE`, `product_id bigint NOT NULL`, `quantity int NOT NULL CHECK (quantity > 0)`, `unit_price numeric(12,2) NOT NULL CHECK (unit_price >= 0)` (price snapshot), `UNIQUE (order_id, product_id)` | unique index also serves lookups by order_id | V1 |
| idempotency_keys | `PRIMARY KEY (user_id uuid, idem_key varchar(64))`, `request_hash char(64) NOT NULL`, `status varchar(16) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED'))`, `response_status int NULL`, `response_body jsonb NULL`, `created_at`, `updated_at`, `CHECK (status = 'IN_PROGRESS' OR response_status IS NOT NULL)` | (created_at) for TTL cleanup | V2 (step 1.7) |
| outbox | `id uuid PK` (= eventId), `aggregate_id uuid NOT NULL`, `topic varchar(255) NOT NULL` (added in 2.2), `event_type varchar(64) NOT NULL`, `payload jsonb NOT NULL`, `created_at`, `sent_at timestamptz NULL`, `attempts int NOT NULL DEFAULT 0 CHECK (attempts >= 0)`, `last_error text NULL` | partial `(created_at) WHERE sent_at IS NULL` | Day 2 (step 2.2, V3) |
| processed_events | `event_id uuid PK`, `event_type varchar(64) NOT NULL`, `processed_at` | PK | V4 (step 2.3) |

Planned Day 2 index: `orders (status, created_at)` for the stuck-order reconciliation job (step 2.6).

### payment_db

| Table | Columns / constraints | Indexes | Flyway |
|---|---|---|---|
| payments | `id uuid PK`, `order_id uuid NOT NULL UNIQUE` (one payment per order: last-line duplicate guard), `amount numeric(12,2) NOT NULL CHECK (amount > 0)`, `status varchar(16) NOT NULL CHECK (status IN ('COMPLETED','FAILED'))`, `failure_reason varchar(64) NULL`, `created_at`, `updated_at` | unique(order_id) | V1 |
| processed_events | same as order_db | PK | V1 |
| outbox | same as order_db | partial, as above | V2 (step 2.3) |

### Deliberate omissions (for incident demos, documented so they are honest)

- `orders (user_id, created_at DESC)` is intentionally **not** created. `GET /api/orders` (own orders,
  paginated) will seq-scan as data grows; incident 007 measures it with `EXPLAIN ANALYZE` and adds it.
- `CHECK (inventory.available >= 0)` arrives with the incident 001 fix, not in V1.

## 3. Maven dependencies (Day 1)

Parent: `spring-boot-starter-parent` 3.5.x (latest patch, verified at scaffold),
BOM `spring-cloud-dependencies` 2025.0.x. Testcontainers versions managed by Boot.
Plugins: `spring-boot-maven-plugin` (services only), surefire runs `*Test`, failsafe runs `*IT`.

| Module | Main | Test |
|---|---|---|
| common-lib | `spring-web` (optional), `jakarta.validation-api` | none |
| gateway-service | `spring-cloud-starter-gateway-server-webflux`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-actuator` | `spring-boot-starter-test`, `spring-security-test` |
| auth-service | `web`, `security`, `oauth2-resource-server` (Nimbus for `JwtEncoder`/JWKS), `data-jpa`, `validation`, `actuator`, `flyway-core`, `flyway-database-postgresql`, `postgresql` (runtime) | `starter-test`, `spring-security-test`, `spring-boot-testcontainers`, `testcontainers:postgresql`, `testcontainers:junit-jupiter` |
| catalog-service | `web`, `data-jpa`, `validation`, `actuator`, `flyway-core`, `flyway-database-postgresql`, `postgresql` | `starter-test`, Testcontainers (3 above) |
| order-service | same as catalog (`RestClient` is in `spring-web`) | same; `MockRestServiceServer` from spring-test |
| payment-service | `web`, `actuator`, `data-jpa`, `flyway-core`, `flyway-database-postgresql`, `postgresql` (skeleton) | `starter-test`, Testcontainers (3 above) |

Scaffold (step 1.1) adds only what the skeletons use; security/oauth2 and validation arrive with
steps 1.2–1.4. Resolved versions: Boot 3.5.16, Spring Cloud 2025.0.3, Maven 3.9.16 (wrapper 3.3.4).

Not added until needed: Redis (Day 2 rate limiting / Day 4 cache), Kafka + Resilience4j (Day 2),
Micrometer Prometheus + tracing (Day 4).

## 4. Decisions

| # | Decision | Chosen | Alternative | Trade-off |
|---|---|---|---|---|
| 1 | Spring Boot line | 3.5.x (per CLAUDE.md) | Boot 4.x | 3.x has more references; 3.5 OSS support has likely ended (verify), Boot 4 upgrade is a later backlog item |
| 2 | ID types | UUID for users/orders/payments/events; bigint for products | bigint everywhere | UUIDs are not enumerable (BOLA) and can be generated before insert; bigint is smaller/faster |
| 3 | Money | `numeric(12,2)` + `BigDecimal` | `long` cents | Readable API vs simpler arithmetic |
| 4 | Identity in catalog/order | Trust `X-User-Id` / `X-User-Roles` set by the gateway (internal network only) | Re-validate JWT per service | Less config/memory vs defence in depth |
| 5 | Roles | Single `role` column | `user_roles` table | Multi-role not needed |
| 6 | Index for incident 007 | Omit `orders(user_id, created_at)` deliberately | Add now | Keeps a real, measurable incident |
| 7 | Migrations | One per feature/incident | All in V1 | Each fix is visible as a migration |
| 8 | Tests | failsafe for `*IT` (containers), surefire for unit | Everything in surefire | Fast `./mvnw test` locally vs one plugin |
| 9 | Gateway test downstream | JDK `com.sun.net.httpserver.HttpServer` | WireMock | No new library vs nicer API |
| 10 | DB users | One Postgres role per service DB | Shared superuser | Ownership isolation vs fewer env vars |
| 11 | Kafka image | `apache/kafka:3.9.1` (official, arm64, KRaft; 3.9.2 amd64 image was broken on 2026-11-15) | `bitnami/kafka` | Bitnami images are no longer freely maintained |
| 12 | Stock change under concurrency (incident 001) | Atomic conditional `UPDATE ... WHERE available >= :qty RETURNING available`; `Inventory` has no setter | Optimistic `@Version` + retry, or `SELECT ... FOR UPDATE` | One statement, no retry storm on a hot row vs the rule must fit in a WHERE. Full comparison: `docs/decisions/001-stock-concurrency.md` |
| 13 | SQL for atomic statements | Spring `JdbcClient` (in spring-jdbc, already on the classpath) for `UPDATE ... RETURNING` / `INSERT ... ON CONFLICT` | JPA `@Modifying @Query` | Returns the new value in one round trip and supports Postgres-specific SQL vs a second data-access style next to JPA |
| 14 | Idempotency claim (incident 002) | `INSERT ... ON CONFLICT DO NOTHING` on `PK (user_id, idem_key)`, each statement in its own short transaction (no tx around the catalog calls) | Catch the duplicate-key exception, or `SELECT ... FOR UPDATE` first | No exception handling inside a transaction vs Postgres-specific SQL. FOR UPDATE can't lock a row that doesn't exist yet Full comparison: `docs/decisions/002-idempotency.md` |
| 15 | Idempotency on failure | Delete the key when the order fails, so the client can retry with the same key | Store the error response (like Stripe) | Retry can succeed after a restock vs a failed request may run twice (safe: failures release their stock) |
| 16 | Request hash | SHA-256 of the parsed request turned back into JSON | Hash of the raw body bytes | Whitespace/field order don't matter vs depends on Jackson output staying stable |
| 17 | Replay response | Stored status + body + same `Location`, plus header `Idempotent-Replayed: true` | Return 200 or 409 on a replay | The client sees exactly what the first call returned vs one extra non-standard header |
| 18 | Create order response (step 2.1) | `202 Accepted` + body + `Location`; order stays PENDING until payment (async) | `201 Created` | Honest about async processing vs Day 1 clients checking 201 must change. `docs/decisions/003-dual-write.md` |
| 19 | Event format on Kafka (step 2.1) | JSON string value (Boot's `StringSerializer` + Jackson), key = orderId, topic declared via `NewTopic` (3 partitions) | Spring Kafka `JsonSerializer` with type headers; broker auto-create | Same JSON the outbox will store (2.2), no Java class names in headers vs manual `writeValueAsString` |
| 20 | Dual write (incident 003) | Transactional outbox + `FOR UPDATE SKIP LOCKED` polling relay (500 ms, batch 100), order + outbox row in one `TransactionTemplate` | Debezium CDC; event-first | Simple, fits the small VM vs poll delay and DB polling. `docs/decisions/003-dual-write.md` |
| 21 | Outbox relay locking | Hold the row locks for the batch while waiting for Kafka acks; stop the batch at the first failure | Lease column (`locked_until`) and send outside a transaction; skip failed rows | Simple and keeps per-order order vs one connection held during sends and head-of-line blocking on a row that can never be sent |
| 22 | Event envelope | `EventEnvelope<T>` in common-lib: eventId (= outbox id), type, version, occurredAt, data | Bare event + Kafka headers for the metadata | Self-contained JSON consumers can dedupe and version on vs a little repetition per message |
| 23 | Producer | `acks=all`, `enable.idempotence=true`, `delivery.timeout.ms` 10 s, relay waits up to 15 s | Defaults (2 min delivery timeout) | Failed sends show up quickly and are retried by the relay vs more retries under a slow broker |
| 24 | Stock release on a failed order (step 2.3) | `order.failed` event via order-service outbox; catalog consumes it with `processed_events` | Order-service calls catalog `/release` over HTTP | Release happens once and survives crashes vs catalog needs Kafka and stock returns a moment later. `docs/decisions/004-saga-stock-release.md` |
| 25 | Consumers | Ack the offset after the DB commit (`manual_immediate`, auto-commit off) + `processed_events` claim in the same transaction | Auto-commit offsets | At-least-once + dedupe vs at-most-once (events can be lost) |
| 26 | Payment provider call | Outside the DB transaction, idempotency key = orderId (mock remembers the first result) | Inside the transaction | No connection held during a slow call vs a duplicate can reach the provider (the key makes it harmless) |
| 27 | Order status change | `UPDATE ... WHERE status = 'PENDING'` (saga, later reconciliation) | JPA `@Version` | Only legal transitions, one statement vs bypasses the JPA entity |
| 28 | Test isolation with one shared Kafka | Random consumer group per test context + `@DirtiesContext` on every IT | Shared group, cached contexts | No cross-test consumption vs a fresh context per IT class |
| 29 | Consumer errors (incident 004) | `DefaultErrorHandler`, exponential backoff 3 attempts (0 / 1 s / 2 s), then `<topic>.DLT` (same partition, error headers) | `@RetryableTopic` non-blocking retry topics | Keeps per-order ordering vs the partition waits up to ~3 s during retries. `docs/decisions/005-dead-letter-topics.md` |
| 30 | Bad data | Jackson parse errors + `InvalidEventException` (listener validation) are not retried | Retry everything | Healthy messages are not delayed vs every listener must validate what it relies on |
| 31 | Shared error policy | `common-lib` `KafkaErrorHandling`, spring-kafka as an optional dependency | Copy the config into 3 services | One policy vs common-lib now knows about Kafka |
| 32 | DLT list/replay | `scripts/dlt.sh` (Kafka CLI in the broker container) | ADMIN HTTP endpoint | No new attack surface vs checked by hand, not by an automated test |
| 33 | Provider protection (incident 005) | Resilience4j (`resilience4j-spring-boot3` 2.2.0, Spring Cloud BOM): Retry > CircuitBreaker > TimeLimiter > ThreadPoolBulkhead, programmatic in `ResilientPaymentGateway` | `@CircuitBreaker`/`@Retry` annotations; Spring Cloud CircuitBreaker | Visible order, unit-testable without AOP vs more code. `docs/decisions/006-provider-resilience.md` |
| 34 | Provider fallback | Payment FAILED `PROVIDER_UNAVAILABLE` → order FAILED → stock released | Pause the consumer while the circuit is open | Fast decision, stock back on sale vs customers lose orders during provider trouble; timeout ≠ not charged (reconciliation todo) |
| 35 | Payment consumer | concurrency 3 (= partitions), `max-poll-records` 10 | 1 thread, 500 records | No head-of-line wait across partitions, worst poll ≪ `max.poll.interval.ms` vs more threads |
