# Decision 001: How to change stock safely under concurrency

**Status:** accepted (step 1.6, incident 001) · **Service:** catalog-service

## Problem
Many buyers reserve the same product at the same moment. The naive read → check in Java → write back
lost updates: 200 buyers succeeded for 50 units (see `docs/incidents/001-overselling.md`).
We need: never sell more than the stock, never lose a release, and keep it simple.

## Options

### A. Atomic conditional UPDATE (chosen)
```sql
UPDATE inventory SET available = available - :qty, updated_at = :now
 WHERE product_id = :id AND available >= :qty
RETURNING available;      -- 0 rows -> 409 OUT_OF_STOCK
```
- **Correctness:** the check and the change are one statement. Postgres row-locks the row. A concurrent
  UPDATE waits, then re-checks the WHERE against the newly committed row (READ COMMITTED re-evaluation),
  so it can't decrement based on an old value.
- **Under contention:** one statement, one round trip, and the row lock is held only until commit. Losers
  aren't retried; they get a fast 409. There is no retry storm.
- **Complexity:** lowest. A few lines of SQL. The logic is in SQL, not in the entity.
- **Limit:** only works when the rule fits in a WHERE clause on one row. All requests still queue on that
  one hot row. That's fine up to the database's per-row update rate. Beyond that, see C2 (Redis Lua
  reservation) in the continuation plan.

### B. Optimistic locking (`@Version` + retry)
`UPDATE ... SET available = ?, version = version + 1 WHERE id = ? AND version = ?`. If 0 rows change,
Hibernate throws `OptimisticLockException` and we re-read and retry.
- **Correctness:** correct, as long as every write goes through the entity.
- **Under contention:** bad for a flash sale. With N buyers on one row, only one wins each round and the
  other N−1 retry, so the work grows roughly with N². Retries need a limit and backoff, and buyers can fail
  with "try again" even when stock is still left.
- **Complexity:** medium. Needs a version column, a retry loop (and a new transaction for each attempt), and
  tests for giving up.
- **When I'd pick it:** low contention, or when the rule needs Java logic across several fields (e.g.
  editing a product's details), where conflicts are rare and retries are cheap.

### C. Pessimistic lock (`SELECT ... FOR UPDATE`)
Read the row with `@Lock(PESSIMISTIC_WRITE)`, check in Java, write, commit.
- **Correctness:** correct. The second transaction blocks on the read until the first commits.
- **Under contention:** the lock is held for the whole read → Java → write → commit, which is longer than A
  (extra round trip). Each waiting buyer also holds a pool connection while blocked, so a hot product can
  use up the Hikari pool (incident 008 territory). Needs lock timeouts.
- **Complexity:** low to medium. Easy to read, but every path must remember to lock, and locking several
  rows in different orders can deadlock.
- **When I'd pick it:** when the check needs data that one UPDATE can't express (several rows, or a
  calculation in Java), and contention is moderate. We already use it in auth for refresh-token rotation.

## Comparison
| | A. Conditional UPDATE | B. Optimistic + retry | C. SELECT FOR UPDATE |
|---|---|---|---|
| Prevents overselling | yes | yes | yes |
| Round trips per reserve | 1 | 2+ (and more on retry) | 2 |
| Behaviour on a hot row | queues briefly, losers get 409 | retry storm | queues, holds connections |
| Code complexity | lowest | highest | medium |
| Expressiveness | one-row WHERE only | anything in Java | anything in Java |

No throughput numbers here. The comparison is qualitative. Only option A was implemented and measured
(correctness: 50/150/0 on 3 of 3 runs). A measured comparison under load is part of Day 4/5.

## Also done
- `CHECK (available >= 0)` (Flyway V2) as a database safety net, so no code path can store negative stock.
- Release is a relative `available = available + :qty`, so concurrent releases can't lose each other.
- `Inventory` has no setter, so the read-modify-write pattern can't come back by accident.

## Not solved here
- Release doesn't check that the units were reserved earlier (it can return more than was taken).
  Idempotency and the saga (steps 1.7, 2.x) make sure each release happens once.
