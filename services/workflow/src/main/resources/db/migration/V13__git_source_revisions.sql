-- M5a: trusted remote branch discovery and immutable source coordinates.
-- Secrets are never stored here: credential_ref may only name a dedicated
-- AETHERIS_GIT_CREDENTIAL_* environment variable.

CREATE TABLE source_repository (
    id                  BIGSERIAL PRIMARY KEY,
    repository_key      VARCHAR(64)  NOT NULL UNIQUE,
    name                VARCHAR(128) NOT NULL,
    remote_url          VARCHAR(512) NOT NULL UNIQUE,
    default_branch      VARCHAR(128) NOT NULL,
    credential_username VARCHAR(128),
    credential_ref      VARCHAR(128),
    status              VARCHAR(16)  NOT NULL DEFAULT 'active',
    current_commit_sha  VARCHAR(40)  NOT NULL,
    last_synced_at      TIMESTAMPTZ  NOT NULL,
    created_by          VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_source_repository_status CHECK (status IN ('active','disabled')),
    CONSTRAINT chk_source_repository_url CHECK (
        remote_url ~ '^https://[A-Za-z0-9.-]+/[A-Za-z0-9._/-]+[.]git$'
        AND remote_url !~ '(^|/)\.\.(/|$)'
    ),
    CONSTRAINT chk_source_repository_branch CHECK (
        default_branch ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$'
        AND default_branch !~ '(^|/)[.]{1,2}(/|$)'
    ),
    CONSTRAINT chk_source_repository_commit CHECK (current_commit_sha ~ '^[0-9a-f]{40}$'),
    CONSTRAINT chk_source_repository_credentials CHECK (
        (credential_ref IS NULL AND credential_username IS NULL)
        OR (credential_ref ~ '^env://AETHERIS_GIT_CREDENTIAL_[A-Z0-9_]{1,64}$'
            AND credential_username IS NOT NULL AND length(credential_username) BETWEEN 1 AND 128)
    )
);

CREATE TABLE source_revision (
    id              BIGSERIAL PRIMARY KEY,
    repository_id   BIGINT       NOT NULL REFERENCES source_repository(id),
    branch_name     VARCHAR(128) NOT NULL,
    commit_sha      VARCHAR(40)  NOT NULL,
    discovered_by   VARCHAR(64)  NOT NULL,
    discovered_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_source_revision UNIQUE (repository_id,commit_sha),
    CONSTRAINT chk_source_revision_commit CHECK (commit_sha ~ '^[0-9a-f]{40}$')
);

CREATE INDEX idx_source_revision_repository
    ON source_revision(repository_id,discovered_at DESC,id DESC);

CREATE TABLE source_repository_event (
    id              BIGSERIAL PRIMARY KEY,
    repository_id   BIGINT       NOT NULL REFERENCES source_repository(id),
    event_type       VARCHAR(32)  NOT NULL,
    actor            VARCHAR(64)  NOT NULL,
    from_commit_sha  VARCHAR(40),
    to_commit_sha    VARCHAR(40),
    details_json     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_source_repository_event_type CHECK (
        event_type IN ('registered','synchronized','enabled','disabled')
    )
);

CREATE INDEX idx_source_repository_event
    ON source_repository_event(repository_id,id DESC);

ALTER TABLE managed_artifact
    ADD COLUMN repository_id BIGINT REFERENCES source_repository(id),
    ADD COLUMN commit_sha VARCHAR(40),
    ADD COLUMN source_path VARCHAR(512),
    ADD CONSTRAINT fk_managed_artifact_revision
        FOREIGN KEY (repository_id,commit_sha)
        REFERENCES source_revision(repository_id,commit_sha),
    ADD CONSTRAINT chk_managed_artifact_source CHECK (
        (repository_id IS NULL AND commit_sha IS NULL AND source_path IS NULL)
        OR (repository_id IS NOT NULL AND commit_sha ~ '^[0-9a-f]{40}$'
            AND source_path ~ '^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*[.]py$'
            AND source_path !~ '(^|/)[.]{1,2}(/|$)')
    );

ALTER TABLE execution_attempt
    ADD COLUMN source_repository_id BIGINT REFERENCES source_repository(id),
    ADD COLUMN source_commit_sha VARCHAR(40),
    ADD COLUMN source_path VARCHAR(512),
    ADD CONSTRAINT chk_execution_attempt_source CHECK (
        (source_repository_id IS NULL AND source_commit_sha IS NULL AND source_path IS NULL)
        OR (source_repository_id IS NOT NULL AND source_commit_sha ~ '^[0-9a-f]{40}$'
            AND source_path IS NOT NULL)
    );
