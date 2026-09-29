-- R3: persist the JobManager identity and lifecycle evidence separately from
-- the SQL Gateway session/operation handle. A Gateway handle alone cannot
-- trigger or reconcile checkpoints and savepoints after a service restart.
ALTER TABLE execution_attempt
    ADD COLUMN IF NOT EXISTS flink_job_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS flink_lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'unknown',
    ADD COLUMN IF NOT EXISTS flink_latest_checkpoint_id BIGINT,
    ADD COLUMN IF NOT EXISTS flink_savepoint_path VARCHAR(2048),
    ADD COLUMN IF NOT EXISTS flink_savepoint_request_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS flink_lifecycle_updated_at TIMESTAMPTZ;

ALTER TABLE execution_attempt DROP CONSTRAINT IF EXISTS chk_flink_lifecycle_state;
ALTER TABLE execution_attempt ADD CONSTRAINT chk_flink_lifecycle_state CHECK (
    flink_lifecycle_state IN ('unknown', 'running', 'paused', 'stopping', 'stopped',
                              'failed', 'cancelled', 'upgrading')
);

CREATE INDEX IF NOT EXISTS idx_execution_attempt_flink_job
    ON execution_attempt (flink_job_id)
    WHERE flink_job_id IS NOT NULL;
