-- M1 execution foundation: keep one logical instance per task/business date, while
-- preserving every physical execution attempt and fencing stale workers.

ALTER TABLE task_instance DROP CONSTRAINT IF EXISTS chk_task_instance_status;
ALTER TABLE task_instance ADD CONSTRAINT chk_task_instance_status CHECK (
    status IN ('pending', 'running', 'success', 'failed', 'skipped',
               'submitting', 'cancel_requested', 'cancelled', 'timed_out', 'unknown')
);

-- A running row left by an older process has no lease or remote handle. Mark it
-- unknown instead of claiming success/failure during migration.
UPDATE task_instance
SET status = 'unknown',
    error_message = COALESCE(error_message, '升级时发现未完成执行，需要人工核对后重跑'),
    updated_at = now()
WHERE status = 'running';

ALTER TABLE task_instance
    ADD COLUMN IF NOT EXISTS active_attempt_id BIGINT,
    ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS execution_attempt (
    id                    BIGSERIAL PRIMARY KEY,
    instance_id           BIGINT       NOT NULL REFERENCES task_instance (id) ON DELETE CASCADE,
    attempt_no            INT          NOT NULL,
    status                VARCHAR(24)  NOT NULL,
    fencing_token         BIGINT       NOT NULL,
    worker_id             VARCHAR(128) NOT NULL,
    trigger_type          VARCHAR(16)  NOT NULL,
    triggered_by          VARCHAR(64),
    task_name             VARCHAR(128) NOT NULL,
    task_sql              TEXT         NOT NULL,
    datasource_id         BIGINT       NOT NULL,
    task_updated_at       TIMESTAMPTZ,
    definition_digest     VARCHAR(64)  NOT NULL,
    parameters_json       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    parameter_digest      VARCHAR(64)  NOT NULL,
    artifact_digest       VARCHAR(128),
    remote_handle         VARCHAR(256),
    application_id        VARCHAR(256),
    queued_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    submitted_at          TIMESTAMPTZ,
    started_at            TIMESTAMPTZ,
    heartbeat_at          TIMESTAMPTZ,
    lease_expires_at      TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    duration_ms           BIGINT,
    affected_rows         BIGINT,
    error_code            VARCHAR(64),
    error_message         VARCHAR(1024),
    CONSTRAINT uk_execution_attempt_no UNIQUE (instance_id, attempt_no),
    CONSTRAINT uk_execution_attempt_fence UNIQUE (instance_id, fencing_token),
    CONSTRAINT chk_execution_attempt_status CHECK (
        status IN ('queued', 'submitting', 'running', 'cancel_requested', 'cancelled',
                   'succeeded', 'failed', 'timed_out', 'unknown', 'skipped')
    ),
    CONSTRAINT chk_execution_attempt_number CHECK (attempt_no > 0),
    CONSTRAINT chk_execution_attempt_fence CHECK (fencing_token > 0)
);

CREATE INDEX IF NOT EXISTS idx_execution_attempt_instance
    ON execution_attempt (instance_id, attempt_no DESC);
CREATE INDEX IF NOT EXISTS idx_execution_attempt_active
    ON execution_attempt (status, lease_expires_at)
    WHERE status IN ('queued', 'submitting', 'running', 'cancel_requested', 'unknown');

ALTER TABLE task_instance
    ADD CONSTRAINT fk_task_instance_active_attempt
    FOREIGN KEY (active_attempt_id) REFERENCES execution_attempt (id) ON DELETE SET NULL;

ALTER TABLE task_log ADD COLUMN IF NOT EXISTS attempt_id BIGINT;
ALTER TABLE task_log
    ADD CONSTRAINT fk_task_log_attempt
    FOREIGN KEY (attempt_id) REFERENCES execution_attempt (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_task_log_attempt ON task_log (attempt_id, id);
