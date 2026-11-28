-- Idempotent consumer for payment events (step 2.3): the event id is inserted in the same transaction as the order
-- status change. A redelivered event hits the primary key and is skipped. Same table as in payment_db.
CREATE TABLE processed_events (
    event_id     uuid        PRIMARY KEY,
    event_type   varchar(64) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);
