# Todo

Open items from reviews, by severity. Each one says where it's planned. Fixed items are removed (git history keeps them).
Source: Day 1 review (step 1.8, 2026-11-23) unless noted.

## High

- [ ] **Reserved stock can still leak.** Incident 003 (step 2.2) fixed the lost event, and a failed order
  transaction now releases its reservations. Still open: catalog times out *after* reserving, or order-service
  crashes between reserve and commit, so the stock stays reserved with no order.
  → Saga (2.3), reconciliation job (2.6).
- [ ] **A Kafka message that always fails is skipped silently.** All consumers (payment, order, catalog) use Spring
  Kafka's default error handler: about 10 quick retries, then a log line and the offset moves on. The event is lost.
  → Step 2.4 (incident 004: retry with backoff + dead-letter topic).
- [ ] **An order can stay PENDING forever** if its payment event never arrives (payment-service down for a long
  time, skipped message). → Step 2.6 (stuck-order reconciliation).
- [ ] **Outbox head-of-line blocking.** The relay stops at the first failed row (to keep order). A row that can
  never be published (e.g. payload over `max.request.size`) blocks all later events. Add max attempts + a
  "parked" state, an alert on `attempts`, or skip rows of other aggregates. → Step 2.4 (poison messages) or 2.6.
- [ ] **No rate limit on login/register** (brute force, credential stuffing, sign-up spam).
  → Step 2.7 (Redis rate limiting in the gateway).
- [ ] **On the VM, only the gateway may be reachable.** catalog and order trust `X-User-Id` / `X-User-Roles`,
  and `/internal/inventory/**` has no auth. In compose: publish only the gateway port (and Grafana later),
  keep services on the internal network. Locally this is done (run-local.sh binds 127.0.0.1).
  → Step 2.8. Optional later: a shared secret or mTLS between services (defence in depth).

## Medium

- [ ] **Gateway returns 500 when a downstream service is down** (connection refused); no timeout or circuit breaker.
  → Step 2.5 (Resilience4j, incident 005).
- [ ] **VM must not run with the `dev` profile** (it seeds 10k products). run-local.sh defaults to `dev`.
  → Step 2.8 (compose sets the profile explicitly).
- [ ] **Status changes must stay conditional.** Since 2.3b the saga moves orders with `UPDATE ... WHERE status = 'PENDING'`
  (no `@Version` needed). The reconciliation job (2.6) must use the same rule, or it can overwrite a saga result.

## Low

- [ ] **Sent outbox rows are never deleted** (source: step 2.2). Add a daily `DELETE FROM outbox WHERE sent_at < now() - 7 days`
  like the idempotency cleanup. → Day 4 tuning (or with 2.6).
- [ ] **Order total can overflow `numeric(12,2)`**: price allows 10 integer digits, × quantity 100 × 50 lines.
  The save fails with a 500 *after* stock was reserved (since 2.2 the reservations are released, but the user gets a 500).
  Fix: cap the order total (or the price) and reject with 422 before reserving.
- [ ] **Expired/revoked refresh tokens are never deleted.** Add a scheduled cleanup (like the idempotency one)
  and an index on `expires_at`.
- [ ] **Idempotency key stuck IN_PROGRESS after a crash** → 409 until the hourly cleanup (up to about 25h).
  Possible: treat IN_PROGRESS older than a few minutes as abandoned. (From step 1.7.)
- [ ] **Product sort by name/price has no index** (full sort). Fine at 10k rows; measure on Day 4.
- [ ] **No `aud` (audience) claim in the JWT.** Only one audience exists today; add it if more clients/APIs appear.
- [ ] **No request body size limit at the gateway.** Add one with rate limiting (2.7) or the VM setup.
- [ ] **A request with an invalid token on a public route gets 401** (the resource server checks any bearer token).
  Standard behaviour; revisit only if clients complain.

## Deliberate (kept for incident demos)

- [ ] Order list loads items lazily per order (N+1). → Incident 006, Day 4.
- [ ] No index on `orders (user_id, created_at)`. → Incident 007, Day 4.
