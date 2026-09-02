ALTER TABLE ingestion_task
    ADD COLUMN IF NOT EXISTS source_datasource_id BIGINT,
    ADD COLUMN IF NOT EXISTS target_datasource_id BIGINT,
    ADD COLUMN IF NOT EXISTS source_sql TEXT,
    ADD COLUMN IF NOT EXISTS write_mode VARCHAR(16) NOT NULL DEFAULT 'append',
    ADD COLUMN IF NOT EXISTS batch_size INT NOT NULL DEFAULT 500,
    ADD COLUMN IF NOT EXISTS last_run_rows BIGINT,
    ADD COLUMN IF NOT EXISTS last_error VARCHAR(512);

CREATE TABLE IF NOT EXISTS ingestion_run (
    id            BIGSERIAL PRIMARY KEY,
    task_id       BIGINT NOT NULL REFERENCES ingestion_task(id) ON DELETE CASCADE,
    status        VARCHAR(16) NOT NULL,
    rows_read     BIGINT,
    rows_written  BIGINT,
    duration_ms   BIGINT,
    error_message VARCHAR(512),
    triggered_by  VARCHAR(128),
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ingestion_run_task_started
    ON ingestion_run(task_id, started_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ingestion_run_active_task
    ON ingestion_run(task_id) WHERE status = 'running';
