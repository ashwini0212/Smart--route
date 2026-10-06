-- Phase 5: login identities and revocable refresh tokens.

CREATE TABLE app_user (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          VARCHAR(254) NOT NULL,
    -- BCrypt output is 60 characters; the column leaves room for a future algorithm prefix.
    password_hash  VARCHAR(100) NOT NULL,
    full_name      VARCHAR(120) NOT NULL,
    role           VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'DISPATCHER', 'DRIVER', 'VIEWER')),
    -- A DRIVER login is linked to exactly one driver record, and only DRIVER logins are.
    driver_id      BIGINT UNIQUE REFERENCES driver (id),
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,
    CHECK ((role = 'DRIVER') = (driver_id IS NOT NULL)),
    -- The application stores emails lower-cased; this keeps uniqueness case-insensitive even for manual SQL.
    CHECK (email = lower(email))
);

CREATE UNIQUE INDEX uq_app_user_email ON app_user (email);

-- Only a SHA-256 hash of each refresh token is stored. A database leak does not reveal usable tokens.
-- Tokens rotate on every use; all tokens descending from one login share a family_id, so reuse of an
-- already-rotated token (a sign it was stolen) revokes the whole family.
CREATE TABLE refresh_token (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    family_id    UUID        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
-- "Revoke every session of this user" (logout everywhere, account disabled) only touches live tokens.
CREATE INDEX idx_refresh_token_user_live ON refresh_token (user_id) WHERE revoked_at IS NULL;
