-- R3: distinguish a stop-with-savepoint used for pause from a terminal stop.
-- The intent is persisted so a service restart cannot turn a pause request into
-- an incorrect stopped state before the asynchronous savepoint completes.
ALTER TABLE execution_attempt
    ADD COLUMN IF NOT EXISTS flink_lifecycle_intent VARCHAR(16);

ALTER TABLE execution_attempt DROP CONSTRAINT IF EXISTS chk_flink_lifecycle_intent;
ALTER TABLE execution_attempt ADD CONSTRAINT chk_flink_lifecycle_intent CHECK (
    flink_lifecycle_intent IS NULL OR flink_lifecycle_intent IN ('pause', 'stop', 'resume', 'upgrade')
);

CREATE INDEX IF NOT EXISTS idx_execution_attempt_flink_intent
    ON execution_attempt (flink_lifecycle_intent)
    WHERE flink_lifecycle_intent IS NOT NULL;
