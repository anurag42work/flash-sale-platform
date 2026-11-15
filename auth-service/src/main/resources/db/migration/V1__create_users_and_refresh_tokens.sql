CREATE TABLE users (
    id            uuid         PRIMARY KEY,
    -- Normalised to lower case in the application before insert.
    email         varchar(254) NOT NULL,
    password_hash varchar(100) NOT NULL,
    role          varchar(16)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Opaque refresh tokens: only the SHA-256 hash is stored, never the token itself.
-- family_id groups a chain of rotated tokens so reuse of a revoked token can revoke the whole family.
CREATE TABLE refresh_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  char(64)    NOT NULL,
    family_id   uuid        NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz NULL,
    replaced_by uuid        NULL REFERENCES refresh_tokens (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family_id ON refresh_tokens (family_id);
