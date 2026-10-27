-- Browser logout revokes one family without changing durable execution approval versions.
-- Existing tokens have no family and must reauthenticate after the coordinated Gateway/Auth upgrade.
CREATE TABLE browser_session (
    sid                     UUID PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    username                VARCHAR(64) NOT NULL,
    access_version          BIGINT NOT NULL CHECK (access_version >= 0),
    account_session_version BIGINT NOT NULL CHECK (account_session_version >= 0),
    refresh_version         BIGINT NOT NULL DEFAULT 0 CHECK (refresh_version >= 0),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at              TIMESTAMPTZ NOT NULL,
    revoked_at              TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);

CREATE INDEX browser_session_user_expiry_idx ON browser_session(user_id, expires_at);
CREATE INDEX browser_session_expiry_idx ON browser_session(expires_at);

COMMENT ON TABLE browser_session IS 'Browser-only authority: missing, expired or revoked family denies access; never infer family from username';
