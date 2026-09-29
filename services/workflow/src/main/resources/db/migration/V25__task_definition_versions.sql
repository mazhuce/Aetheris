-- R3: every task definition change gets a monotonic version. Attempts retain
-- the exact version they claimed, independent of later task edits/upgrades.
ALTER TABLE task
    ADD COLUMN IF NOT EXISTS definition_version INT NOT NULL DEFAULT 1;
ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_definition_version;
ALTER TABLE task ADD CONSTRAINT chk_task_definition_version CHECK (definition_version >= 1);

ALTER TABLE execution_attempt
    ADD COLUMN IF NOT EXISTS task_definition_version INT;

CREATE INDEX IF NOT EXISTS idx_execution_attempt_task_definition_version
    ON execution_attempt (instance_id, task_definition_version);
