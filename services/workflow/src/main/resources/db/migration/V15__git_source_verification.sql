-- M5b: cryptographically bound Git blobs and append-only verification evidence.

ALTER TABLE source_repository
    ADD COLUMN signature_policy VARCHAR(16) NOT NULL DEFAULT 'optional',
    ADD COLUMN trusted_signer_fingerprints VARCHAR(4096),
    ADD CONSTRAINT chk_source_repository_signature_policy
        CHECK (signature_policy IN ('required','optional')),
    ADD CONSTRAINT chk_source_repository_trusted_signers
        CHECK ((signature_policy = 'optional' OR trusted_signer_fingerprints IS NOT NULL)
            AND (trusted_signer_fingerprints IS NULL
                OR trusted_signer_fingerprints ~ '^([0-9a-f]{40}|[0-9a-f]{64})(,([0-9a-f]{40}|[0-9a-f]{64}))*$'));

ALTER TABLE source_repository_event
    DROP CONSTRAINT chk_source_repository_event_type,
    ADD CONSTRAINT chk_source_repository_event_type CHECK (
        event_type IN ('registered','synchronized','enabled','disabled','trust_updated')
    );

ALTER TABLE source_revision
    ADD COLUMN signature_status VARCHAR(16) NOT NULL DEFAULT 'not_checked',
    ADD COLUMN signer_fingerprint VARCHAR(64),
    ADD COLUMN signature_verified_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_source_revision_signature_status
        CHECK (signature_status IN ('not_checked','verified','unsigned')),
    ADD CONSTRAINT chk_source_revision_signer
        CHECK ((signature_status IN ('not_checked','unsigned') AND signer_fingerprint IS NULL)
            OR (signature_status = 'verified' AND signer_fingerprint ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'));

ALTER TABLE managed_artifact
    ADD COLUMN source_blob_id VARCHAR(64),
    ADD COLUMN source_blob_sha256 VARCHAR(64),
    ADD COLUMN source_blob_size BIGINT,
    ADD COLUMN source_signature_status VARCHAR(16),
    ADD COLUMN source_signer_fingerprint VARCHAR(64),
    ADD COLUMN source_verified_by VARCHAR(64),
    ADD COLUMN source_verified_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_managed_artifact_blob_evidence CHECK (
        (source_blob_id IS NULL AND source_blob_sha256 IS NULL AND source_blob_size IS NULL
            AND source_signature_status IS NULL AND source_verified_at IS NULL)
        OR (source_blob_id ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'
            AND source_blob_sha256 ~ '^[0-9a-f]{64}$'
            AND source_blob_size BETWEEN 1 AND 8388608
            AND source_signature_status IN ('verified','unsigned')
            AND ((source_signature_status = 'unsigned' AND source_signer_fingerprint IS NULL)
                OR (source_signature_status = 'verified'
                    AND source_signer_fingerprint ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'))
            AND source_verified_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_managed_artifact_signer
        CHECK (source_signer_fingerprint IS NULL
            OR source_signer_fingerprint ~ '^([0-9a-f]{40}|[0-9a-f]{64})$');

CREATE TABLE source_verification (
    id                  BIGSERIAL PRIMARY KEY,
    artifact_id         BIGINT       NOT NULL REFERENCES managed_artifact(id),
    repository_id       BIGINT       NOT NULL REFERENCES source_repository(id),
    commit_sha          VARCHAR(40)  NOT NULL,
    source_path         VARCHAR(512) NOT NULL,
    blob_id             VARCHAR(64)  NOT NULL,
    blob_sha256         VARCHAR(64)  NOT NULL,
    blob_size           BIGINT       NOT NULL,
    signature_status    VARCHAR(16)  NOT NULL,
    signer_fingerprint  VARCHAR(64),
    purpose             VARCHAR(16)  NOT NULL,
    verified_by         VARCHAR(64)  NOT NULL,
    verified_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_source_verification_revision
        FOREIGN KEY (repository_id,commit_sha)
        REFERENCES source_revision(repository_id,commit_sha),
    CONSTRAINT chk_source_verification_commit CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    CONSTRAINT chk_source_verification_blob CHECK (
        blob_id ~ '^([0-9a-f]{40}|[0-9a-f]{64})$' AND blob_sha256 ~ '^[0-9a-f]{64}$'
        AND blob_size BETWEEN 1 AND 8388608
    ),
    CONSTRAINT chk_source_verification_signature CHECK (
        (signature_status = 'unsigned' AND signer_fingerprint IS NULL)
        OR (signature_status = 'verified' AND signer_fingerprint ~ '^([0-9a-f]{40}|[0-9a-f]{64})$')
    ),
    CONSTRAINT chk_source_verification_purpose
        CHECK (purpose IN ('registration','manual','approval','publication'))
);

CREATE INDEX idx_source_verification_artifact
    ON source_verification(artifact_id,verified_at DESC,id DESC);

CREATE TRIGGER source_verification_immutable
    BEFORE UPDATE OR DELETE ON source_verification
    FOR EACH ROW EXECUTE FUNCTION reject_git_source_history_mutation();

ALTER TABLE execution_attempt
    ADD COLUMN source_blob_id VARCHAR(64),
    ADD COLUMN source_blob_sha256 VARCHAR(64),
    ADD COLUMN source_signature_status VARCHAR(16),
    ADD COLUMN source_signer_fingerprint VARCHAR(64),
    ADD COLUMN source_verified_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_execution_attempt_blob_evidence CHECK (
        (source_blob_id IS NULL AND source_blob_sha256 IS NULL
            AND source_signature_status IS NULL AND source_verified_at IS NULL)
        OR (source_blob_id ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'
            AND source_blob_sha256 ~ '^[0-9a-f]{64}$'
            AND source_signature_status IN ('verified','unsigned')
            AND ((source_signature_status = 'unsigned' AND source_signer_fingerprint IS NULL)
                OR (source_signature_status = 'verified'
                    AND source_signer_fingerprint ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'))
            AND source_verified_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_execution_attempt_signer
        CHECK (source_signer_fingerprint IS NULL
            OR source_signer_fingerprint ~ '^([0-9a-f]{40}|[0-9a-f]{64})$');
