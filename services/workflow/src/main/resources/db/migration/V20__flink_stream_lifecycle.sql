-- R3: distinguish bounded Flink SQL from continuously running CDC/stream jobs.
ALTER TABLE task
    ADD COLUMN IF NOT EXISTS flink_mode VARCHAR(16) NOT NULL DEFAULT 'streaming';

ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_flink_mode;
ALTER TABLE task ADD CONSTRAINT chk_flink_mode
    CHECK (flink_mode IN ('bounded', 'streaming'));

ALTER TABLE execution_attempt
    ADD COLUMN IF NOT EXISTS flink_mode VARCHAR(16);

UPDATE execution_attempt
SET flink_mode = 'streaming'
WHERE task_type = 'flink' AND flink_mode IS NULL;
