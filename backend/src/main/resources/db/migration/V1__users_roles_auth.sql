-- Phase 1: identity and authentication.
--
-- UUID primary keys are assigned by the application (Hibernate @UuidGenerator),
-- not by the database, so there are no column defaults here.

CREATE TABLE users (
    id            UUID        PRIMARY KEY,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    full_name     TEXT        NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_users_email_shape CHECK (position('@' in email) > 1),
    CONSTRAINT ck_users_full_name_present CHECK (length(btrim(full_name)) > 0)
);

-- Case-insensitive uniqueness without the citext extension. The application
-- also lowercases on write; this index is what actually enforces it.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));


CREATE TABLE roles (
    id   UUID PRIMARY KEY,
    name TEXT NOT NULL,

    CONSTRAINT uq_roles_name UNIQUE (name),
    CONSTRAINT ck_roles_name_known CHECK (name IN ('USER', 'ADMIN'))
);


CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id),

    PRIMARY KEY (user_id, role_id)
);

-- Reverse lookup: "who are the admins?" without scanning.
CREATE INDEX ix_user_roles_role ON user_roles (role_id);


-- Refresh tokens are opaque random strings, never JWTs, and only their SHA-256
-- hash is stored. A leaked database therefore does not yield usable tokens.
--
-- Rotation: each use revokes the current token and issues a successor, linked
-- by replaced_by_id. If a token that is already revoked is presented again,
-- that is token theft, and the whole chain for that user gets revoked.
CREATE TABLE refresh_tokens (
    id             UUID        PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash     TEXT        NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    replaced_by_id UUID        REFERENCES refresh_tokens(id),

    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

-- Partial index over live tokens only: the cleanup job and the "revoke every
-- session for this user" path both scan exclusively on these.
CREATE INDEX ix_refresh_tokens_active ON refresh_tokens (user_id, expires_at)
    WHERE revoked_at IS NULL;


-- The two roles the application knows about. Fixed UUIDs so that fixtures,
-- tests, and seed data can reference them without a lookup.
INSERT INTO roles (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'USER'),
    ('00000000-0000-0000-0000-000000000002', 'ADMIN');
