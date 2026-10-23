-- Independent of refresh rotation: account security changes revoke access JWTs.
ALTER TABLE auth.auth_user ADD COLUMN access_version BIGINT NOT NULL DEFAULT 0;
