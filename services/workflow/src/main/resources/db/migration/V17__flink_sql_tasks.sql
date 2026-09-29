-- Flink SQL Gateway tasks share the workflow lease/attempt model with SQL and Spark.
-- The endpoint is stored on the task so one workflow can target more than one gateway.
ALTER TABLE task ADD COLUMN IF NOT EXISTS flink_endpoint VARCHAR(512);
ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_type;
ALTER TABLE task ADD CONSTRAINT chk_task_type CHECK (task_type IN ('sql', 'spark', 'flink', 'quality'));
ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_definition_by_type;
ALTER TABLE task ADD CONSTRAINT chk_task_definition_by_type CHECK (
    (task_type = 'sql' AND datasource_id IS NOT NULL AND task_sql IS NOT NULL
        AND job_definition_id IS NULL AND quality_rule_id IS NULL AND flink_endpoint IS NULL)
    OR (task_type = 'spark' AND job_definition_id IS NOT NULL
        AND datasource_id IS NULL AND task_sql IS NULL AND quality_rule_id IS NULL AND flink_endpoint IS NULL)
    OR (task_type = 'flink' AND task_sql IS NOT NULL AND flink_endpoint IS NOT NULL
        AND datasource_id IS NULL AND job_definition_id IS NULL AND quality_rule_id IS NULL)
    OR (task_type = 'quality' AND quality_rule_id IS NOT NULL
        AND datasource_id IS NULL AND task_sql IS NULL AND job_definition_id IS NULL AND flink_endpoint IS NULL)
);

ALTER TABLE execution_attempt ADD COLUMN IF NOT EXISTS flink_endpoint VARCHAR(512);
ALTER TABLE execution_attempt DROP CONSTRAINT IF EXISTS chk_attempt_task_type;
ALTER TABLE execution_attempt ADD CONSTRAINT chk_attempt_task_type
    CHECK (task_type IN ('sql', 'spark', 'flink', 'quality'));

