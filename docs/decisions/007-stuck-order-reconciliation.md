# Decision 007: Settling orders stuck in PENDING

**Status:** accepted (step 2.6) · **Services:** order-service (job), payment-service (internal endpoint)

## Problem
An order leaves PENDING only when a payment event arrives. If that never happens, the order stays PENDING forever
and its stock stays reserved. Causes: payment-service down for a long time, `order.created` or `payment.*` parked
on a DLT (incident 004), or an outbox relay blocked by a bad row. We need a safety net that settles such orders, is
safe to run on several instances, and doesn't fight with a late payment event.

## 1. How the job learns the truth: ask payment-service over HTTP (chosen)
`POST /internal/payments/{orderId}/expire` returns the order's payment, or creates `FAILED / EXPIRED` if there is
none, and returns that.

- **Why "expire" and not just "get status":** "no payment" might mean the `OrderCreated` is still on its way (in a
  DLT, behind a stuck relay). If we only read the status and failed the order, a later `OrderCreated` would charge
  the customer for an order that already failed and released its stock. The expire row closes that door:
  payment-service now skips charging any order that already has a payment row. `UNIQUE (order_id)` makes the insert
  race-safe against `PaymentService.handle`.
- **Remaining race:** a charge that is *in flight* at the provider while the order expires (the payment row is
  written only after the provider answers). The customer is charged, the payment insert loses, and the order is
  FAILED. The real fix: payment-service writes a PROCESSING row before it charges, and expire then sees "in
  progress" and waits. Not done here (docs/todo.md). With `stuck-after` = 10 min and a 2 s provider timeout, the
  window is a charge that started in the last seconds before the 10-minute mark.
- **Alternative:** order-service re-publishes `OrderCreated` for stuck orders. That doesn't help when payment-service
  is down or the event is poison, and it can't learn "payment happened but its event is stuck".
- **Alternative:** a shared database view. Not allowed: one database per service, no cross-service reads.

## 2. One instance at a time: lease lock table `job_locks` (chosen; ShedLock declined by the user)
```sql
INSERT INTO job_locks (name, locked_until, locked_by) VALUES (:name, now() + :lease, :owner)
ON CONFLICT (name) DO UPDATE SET locked_until = EXCLUDED.locked_until, locked_by = EXCLUDED.locked_by
    WHERE job_locks.locked_until < now()
```
One row affected means we hold it. At the end: `locked_until = now()` (only if `locked_by` is still us).

| Option | Pros | Cons |
|---|---|---|
| **Lease lock table (chosen)** | Two statements; a dead holder's lease expires by itself; DB clock (no skew between instances); no transaction held during the run | Our own code; the lease must be longer than the longest run (10 min vs ≤ 5 min worst case) |
| ShedLock | Same lease idea, well tested, `lockAtLeastFor` against very short runs on skewed clocks | A new library; hides the mechanism |
| `pg_try_advisory_xact_lock` | No table | Needs a transaction open for the whole run, including HTTP calls; the session-level variant is easy to leak with pooled connections |
| `FOR UPDATE SKIP LOCKED` on the orders | Spreads the work over instances (like the outbox relay) | Holds row locks and a transaction during HTTP calls |

**The lock is an optimisation, not the safety guarantee.** Every change goes through the saga's conditional
`UPDATE ... WHERE status = 'PENDING'`, and an `OrderFailed` is written only on a real transition. Mutation check
(2026-11-28): with the lease condition removed, both instances ran, payment-service got 40 calls instead of 20, and
still every one of the 20 orders was failed exactly once (7 + 13 failed, the rest "unchanged").

## 3. What "stuck" means: PENDING longer than 10 minutes (configurable)
- A healthy order leaves PENDING within seconds (provider ~100 ms, outbox polls 500 ms, circuit breaker fallback
  in seconds). 10 minutes means really stuck, and it leaves time to replay a DLT before the job gives up.
- Shorter: more orders reach the race in section 1, and DLT replays come too late. Longer: stock stays reserved
  longer during an outage.
- A partial index `orders (created_at) WHERE status = 'PENDING'` keeps the query cheap however many orders are
  already settled.

## 4. Bounded runs
`batch-size` 100 per run, payment client timeouts 1 s + 2 s, so the worst run is 300 s, under the 10 min lease. If
payment-service is down, the orders stay PENDING and the next run (60 s later) tries again. Nothing is lost.

## Not covered
- **An order that was never saved** (crash between reserving at catalog and committing): the job can't see it, and
  catalog keeps the reservation. That needs reservations with a TTL in catalog (docs/todo.md).
- **Payments FAILED with `PROVIDER_UNAVAILABLE`** that the provider did charge: the job takes payment-service's
  word. Provider-side reconciliation is still on the todo list.
