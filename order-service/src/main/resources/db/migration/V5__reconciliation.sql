-- Step 2.6: stuck-order reconciliation.

-- A lease lock per scheduled job, so only one order-service instance runs a job at a time. Taken with
--   INSERT ... ON CONFLICT (name) DO UPDATE ... WHERE job_locks.locked_until < now()
-- (1 row affected = we got it). If the holder dies, the lease simply runs out. now() is the DATABASE clock,
-- so instances with skewed clocks still agree.
CREATE TABLE job_locks (
    name         varchar(64)  PRIMARY KEY,
    locked_until timestamptz  NOT NULL,
    locked_by    varchar(128) NOT NULL
);

-- The job asks "PENDING and older than X, oldest first". A partial index holds only PENDING orders, so it stays
-- tiny however many CONFIRMED/FAILED orders pile up (most orders leave PENDING within seconds).
CREATE INDEX idx_orders_pending_created_at ON orders (created_at) WHERE status = 'PENDING';
