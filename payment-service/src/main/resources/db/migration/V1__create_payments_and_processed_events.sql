-- UNIQUE (order_id): at most one payment per order, the last line of defence
-- against duplicate processing of the same order.created event.
CREATE TABLE payments (
    id             uuid          PRIMARY KEY,
    order_id       uuid          NOT NULL,
    amount         numeric(12,2) NOT NULL,
    status         varchar(16)   NOT NULL,
    failure_reason varchar(64)   NULL,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_order_id UNIQUE (order_id),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_status CHECK (status IN ('COMPLETED', 'FAILED'))
);

-- Idempotent consumer: the event id is inserted in the same transaction as the payment.
-- A duplicate delivery hits the primary key and is skipped.
CREATE TABLE processed_events (
    event_id     uuid        PRIMARY KEY,
    event_type   varchar(64) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);
