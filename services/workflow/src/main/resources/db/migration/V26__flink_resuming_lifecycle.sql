-- The resume transition is persisted before the replacement SQL operation is
-- submitted.  The earlier state constraint omitted this legitimate R3 state.
ALTER TABLE execution_attempt DROP CONSTRAINT IF EXISTS chk_flink_lifecycle_state;
ALTER TABLE execution_attempt ADD CONSTRAINT chk_flink_lifecycle_state CHECK (
    flink_lifecycle_state IN ('unknown', 'running', 'paused', 'stopping', 'stopped',
                              'failed', 'cancelled', 'upgrading', 'resuming')
);

-- A remote JobManager job has one active workflow owner across replicas.
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_flink_job_owner
    ON execution_attempt (flink_job_id)
    WHERE flink_job_id IS NOT NULL AND status IN ('running', 'cancel_requested', 'unknown');
