-- R3: retain the requested replacement SQL and digest while a streaming job
-- drains to a savepoint. The original attempt definition remains immutable;
-- this is an explicit, auditable upgrade transition.
ALTER TABLE execution_attempt
    ADD COLUMN IF NOT EXISTS flink_upgrade_task_sql TEXT,
    ADD COLUMN IF NOT EXISTS flink_upgrade_definition_digest VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_execution_attempt_flink_upgrade
    ON execution_attempt (flink_lifecycle_intent)
    WHERE flink_lifecycle_intent = 'upgrade';
