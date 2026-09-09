-- M3 recovery closure: a manager may resolve only a handle-less unknown Spark
-- attempt after checking external evidence. The decision is append-only and
-- remains tied to the exact fenced attempt that was resolved.

CREATE TABLE execution_resolution (
    id              BIGSERIAL PRIMARY KEY,
    instance_id     BIGINT       NOT NULL REFERENCES task_instance(id),
    attempt_id      BIGINT       NOT NULL REFERENCES execution_attempt(id),
    from_status     VARCHAR(24)  NOT NULL DEFAULT 'unknown',
    resolved_status VARCHAR(24)  NOT NULL,
    reason          VARCHAR(512) NOT NULL,
    evidence_ref    VARCHAR(512) NOT NULL,
    resolved_by     VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_execution_resolution_attempt UNIQUE (instance_id, attempt_id),
    CONSTRAINT chk_execution_resolution_from CHECK (from_status = 'unknown'),
    CONSTRAINT chk_execution_resolution_target CHECK (
        resolved_status IN ('success', 'failed', 'cancelled')
    ),
    CONSTRAINT chk_execution_resolution_reason CHECK (char_length(trim(reason)) BETWEEN 1 AND 512),
    CONSTRAINT chk_execution_resolution_evidence CHECK (char_length(trim(evidence_ref)) BETWEEN 1 AND 512),
    CONSTRAINT chk_execution_resolution_actor CHECK (char_length(trim(resolved_by)) BETWEEN 1 AND 64)
);

CREATE INDEX idx_execution_resolution_instance
    ON execution_resolution(instance_id, created_at DESC);

CREATE OR REPLACE FUNCTION reject_execution_resolution_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'execution_resolution is append-only';
END;
$$;

CREATE TRIGGER execution_resolution_immutable
    BEFORE UPDATE OR DELETE ON execution_resolution
    FOR EACH ROW EXECUTE FUNCTION reject_execution_resolution_mutation();
