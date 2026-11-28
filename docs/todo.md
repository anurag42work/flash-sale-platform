# Todo

Open items from reviews, by severity. Each one says where it's planned. Fixed items are removed (git history keeps them).
Source: Day 1 review (step 1.8, 2026-11-23) unless noted.

## High

- [ ] **Reserved stock can still leak.** Incident 003 (step 2.2) fixed the lost event, and a failed order
  transaction now releases its reservations. Still open: catalog times out *after* reserving, or order-service
  crashes between reserve and commit, so the stock stays reserved with no order. The reconciliation job (2.6) can't
  see these (there is no order row). Fix: reservations with a TTL in catalog (reserved_until + a sweeper), confirmed
  by the order. → Continuation plan.
- [ ] **A payment timeout is not proof that nobody was charged** (source: step 2.5). Payments FAILED with
  `PROVIDER_UNAVAILABLE` may have been charged by a real provider after we gave up. Needs a reconciliation job that
  asks the provider for the final status (refund or confirm) and/or provider webhooks. The 2.6 job only asks
  payment-service, not the provider. → Continuation plan.
- [ ] **Charge in flight while the order expires** (source: step 2.6). payment-service writes the payment row only after
  the provider answers, so `expire` can win while a charge is running: customer charged, order FAILED. Fix: insert a
  PROCESSING payment row before charging; `expire` then reports "in progress" and the job waits. → Continuation plan.
- [ ] **Outbox head-of-line blocking.** The relay stops at the first failed row (to keep order). A row that can
  never be published (e.g. payload over `max.request.size`) blocks all later events. Add max attempts + a
  "parked" state, an alert on `attempts`, or skip rows of other aggregates. Not covered by 2.4 (consumers) or 2.6
  (the job settles the stuck orders, but the relay stays blocked). → Day 4 (alert) / continuation plan.
- [ ] **On the VM, only the gateway may be reachable.** catalog and order trust `X-User-Id` / `X-User-Roles`,
  and `/internal/**` (catalog inventory, payment expire) has no auth. In compose: publish only the gateway port (and Grafana later),
  keep services on the internal network. Locally this is done (run-local.sh binds 127.0.0.1).
  → Step 2.8. Optional later: a shared secret or mTLS between services (defence in depth).
- [ ] **Credential stuffing over many IPs** (source: step 2.7). The login limit is per IP (5/min), so a botnet stays under
  it. Add a per-account limit (key = email from the body) and/or lockout with growing delays in auth-service. → Continuation plan.

## Medium

- [ ] **Gateway returns 500 when a downstream service is down** (connection refused); no timeout or circuit breaker.
  → Not covered by 2.5 (that was the payment provider). Planned: Spring Cloud CircuitBreaker + timeouts on gateway routes, before 2.8.
- [ ] **VM must not run with the `dev` profile** (it seeds 10k products). run-local.sh defaults to `dev`.
  → Step 2.8 (compose sets the profile explicitly).
- [ ] **Rate limiter running open unnoticed** (source: step 2.7). With Redis down, limits are off (fail open, by design).
  Alert on Redis being unreachable / limiter errors. → Day 4 (step 4.1).
- [ ] **No alert when messages land on a DLT** (source: step 2.4). Add a metric (DLT end offsets or a counter in
  the recoverer) and a Grafana alert. → Day 4 (step 4.1).

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
