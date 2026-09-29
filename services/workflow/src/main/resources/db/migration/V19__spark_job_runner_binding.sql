ALTER TABLE workflow.spark_job_definition
    ADD COLUMN IF NOT EXISTS runner_id BIGINT REFERENCES workflow.compute_runner(id);

CREATE INDEX IF NOT EXISTS idx_spark_job_runner
    ON workflow.spark_job_definition(runner_id)
    WHERE runner_id IS NOT NULL;
