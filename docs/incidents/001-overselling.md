# Incident 001: Overselling under concurrent reservations

| | |
|---|---|
| Status | Reproduced (step 1.5). Fix: step 1.6 |
| Service | catalog-service, `POST /internal/inventory/{productId}/reserve` |
| Branch | `incident/001-overselling` |
| Test | `catalog-service/src/test/java/com/flashsale/catalog/inventory/InventoryConcurrencyIT.java` |

## Symptom
In a flash sale, many buyers reserve the same product at the same moment. The service sells more units
than it has. Every buyer gets `200 OK`, and the stock count in the database stays above zero, so it
doesn't match what was actually sold.

## How it was reproduced
- Testcontainers Postgres 16 and the real HTTP endpoint (the same call order-service makes).
- The test creates a product with **50** units in stock.
- **200** threads each send `reserve(quantity = 1)`. Two latches make them all start together:
  `ready` waits until all 200 threads exist, then `start` opens once and releases them all.
- Expected: 50 × `200`, 150 × `409 OUT_OF_STOCK`, final stock 0.
- Command:
  ```
  ./mvnw -pl catalog-service -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
         -Dfailsafe.failIfNoSpecifiedTests=false -Dit.test=InventoryConcurrencyIT
  ```

## Evidence (measured 2026-11-23, before the fix)
Machine: laptop, Intel i5-8265U (4 cores / 8 threads), 8 GB RAM, Windows 11. Docker/WSL2 has 2 CPUs and 3 GB.
Catalog uses a Hikari pool of 10 (the default config).

| Run | 200 OK | 409 | other | Final stock | Units actually taken from stock | Sold but not taken from stock |
|---|---|---|---|---|---|---|
| 1 | 200 | 0 | 0 | 20 | 30 | 170 |
| 2 | 200 | 0 | 0 | 13 | 37 | 163 |
| 3 | 200 | 0 | 0 | 20 | 30 | 170 |

On every run, all 200 buyers got a reservation for 50 units: **150 units oversold**. The stock went down
by only 30–37, so the database doesn't match what was sold either.

## Root cause: lost update (read, check, write)
The naive `InventoryService.reserve` does this:
1. `SELECT available FROM inventory WHERE product_id = ?` (load the JPA entity)
2. In Java: `if (available < qty) throw OutOfStock`
3. `UPDATE inventory SET available = <value computed in Java> WHERE product_id = ?` (at flush/commit)

Postgres runs at READ COMMITTED by default, and there is no lock. Two transactions can read the same value:

```
time  Tx A (buyer 1)                       Tx B (buyer 2)                       DB available
t1    BEGIN; SELECT available -> 50                                             50
t2                                         BEGIN; SELECT available -> 50        50
t3    check 50 >= 1 ok, compute 49
t4                                         check 50 >= 1 ok, compute 49
t5    UPDATE SET available = 49; COMMIT                                         49
t6                                         UPDATE SET available = 49            (waits on A's row lock, then runs)
t7                                         COMMIT                               49   <- should be 48
```
Both buyers got a unit, but stock went down by one. B's write overwrote A's write: a **lost update**.
The row lock that Postgres takes on UPDATE doesn't help here. B computed its value (49) from an old read,
and B's UPDATE sets that absolute value instead of re-checking anything.

Why the numbers look like this: the pool has 10 connections, so about 10 transactions run at a time.
Each wave of ~10 reads the same stock value and writes "value - 1", so a wave of ~10 sales lowers the
stock by about 1. 200 requests come in roughly 20–30 waves, which is why stock ends at about 13–20 and
never reaches the "< 1" check. So nobody ever gets a 409.

`release` has the same pattern, so concurrent releases also lose updates. That case gives back too
little stock instead of selling too much.

## Fix
To be added in step 1.6: an atomic conditional `UPDATE ... WHERE available >= :qty`, plus `CHECK (available >= 0)`.

## Before / after
| | Before | After |
|---|---|---|
| Successful reservations (50 stock, 200 buyers) | 200, 200, 200 | (step 1.6) |
| Final stock | 20, 13, 20 | (step 1.6) |
