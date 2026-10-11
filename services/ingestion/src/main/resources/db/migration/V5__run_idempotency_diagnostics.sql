ALTER TABLE ingestion_run
    ADD COLUMN IF NOT EXISTS request_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS replay_of_run_id BIGINT REFERENCES ingestion_run(id),
    ADD COLUMN IF NOT EXISTS failure_code VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ingestion_run_task_request
    ON ingestion_run(task_id, request_key)
    WHERE request_key IS NOT NULL;
