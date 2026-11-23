-- Incident 002: one row per (user, Idempotency-Key). The primary key is what decides which of two
-- identical requests wins: the second INSERT hits the conflict and does nothing.
-- Keys are scoped per user, so two users can't collide or read each other's stored responses.
CREATE TABLE idempotency_keys (
    user_id         uuid        NOT NULL,
    idem_key        varchar(64) NOT NULL,
    request_hash    char(64)    NOT NULL, -- SHA-256 hex of the request body (same key + other body = 422)
    status          varchar(16) NOT NULL,
    response_status int         NULL,
    response_body   jsonb       NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, idem_key),
    CONSTRAINT ck_idempotency_keys_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_keys_completed_has_response CHECK (status = 'IN_PROGRESS' OR response_status IS NOT NULL)
);

-- For the cleanup job: DELETE ... WHERE created_at < now() - ttl.
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
