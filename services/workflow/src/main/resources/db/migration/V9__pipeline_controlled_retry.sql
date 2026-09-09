-- M3 controlled retry: every resumed run keeps an immutable link to its source
-- and the first node that will be executed. Unknown outcomes remain non-retryable.

ALTER TABLE pipeline_run
    ADD COLUMN retry_of_run_id BIGINT REFERENCES pipeline_run(id),
    ADD COLUMN resume_from_task_id BIGINT REFERENCES task(id);

ALTER TABLE pipeline_run ADD CONSTRAINT chk_pipeline_retry_link CHECK (
    (retry_of_run_id IS NULL AND resume_from_task_id IS NULL)
    OR (retry_of_run_id IS NOT NULL AND resume_from_task_id IS NOT NULL)
);

CREATE INDEX idx_pipeline_retry_of
    ON pipeline_run(retry_of_run_id)
    WHERE retry_of_run_id IS NOT NULL;
