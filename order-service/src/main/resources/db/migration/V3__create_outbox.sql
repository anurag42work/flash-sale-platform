-- Incident 003 fix: transactional outbox. A row is inserted in the SAME transaction as the order, so the order
-- and "its event must be published" commit (or roll back) together. OutboxRelay publishes unsent rows to Kafka.
CREATE TABLE outbox (
    id           uuid         NOT NULL, -- = eventId inside the payload; consumers dedupe on it
    aggregate_id uuid         NOT NULL, -- the order id; also the Kafka message key (keeps one order's events in order)
    topic        varchar(255) NOT NULL,
    event_type   varchar(64)  NOT NULL,
    payload      jsonb        NOT NULL, -- the full event envelope, published as-is
    created_at   timestamptz  NOT NULL,
    sent_at      timestamptz  NULL,     -- NULL = not published yet
    attempts     int          NOT NULL DEFAULT 0,
    last_error   text         NULL,
    CONSTRAINT pk_outbox PRIMARY KEY (id),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

-- The relay only ever reads unsent rows, oldest first. A partial index stays small: sent rows drop out of it.
CREATE INDEX idx_outbox_unsent ON outbox (created_at) WHERE sent_at IS NULL;
