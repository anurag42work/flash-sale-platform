# Todo

Open items from reviews, by severity. Each one says where it's planned. Fixed items are removed (git history keeps them).
Source: Day 1 review (step 1.8, 2026-11-23) unless noted.

## High

- [ ] **Dual write: reserved stock can leak.** If the order save fails, catalog times out *after* reserving,
  or order-service crashes between reserve and save, the stock stays reserved with no order.
  → Day 2, steps 2.1–2.3 (incident 003: outbox + saga). Reconciliation job: 2.6.
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
- [ ] **`Order` has no `@Version`.** Once the saga updates status from Kafka (2.3) and the reconciliation job
  runs (2.6), two updates could overwrite each other. Add optimistic locking or conditional status updates
  (`UPDATE ... WHERE status = 'PENDING'`). → Steps 2.3 / 2.6.

## Low

- [ ] **Order total can overflow `numeric(12,2)`**: price allows 10 integer digits, × quantity 100 × 50 lines.
  The save fails with a 500 *after* stock was reserved (then it's the dual-write leak above).
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
