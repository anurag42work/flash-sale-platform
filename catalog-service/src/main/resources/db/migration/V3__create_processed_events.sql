-- Idempotent consumer for order.failed (step 2.3): the event id is inserted in the same transaction as the stock
-- release. Release adds stock (available + qty), so processing a redelivered event twice would CREATE stock;
-- the primary key makes the second copy a no-op. Same table as in order_db / payment_db.
CREATE TABLE processed_events (
    event_id     uuid        PRIMARY KEY,
    event_type   varchar(64) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);
