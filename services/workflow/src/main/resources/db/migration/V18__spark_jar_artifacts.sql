ALTER TABLE workflow.managed_artifact
    DROP CONSTRAINT IF EXISTS chk_artifact_type,
    DROP CONSTRAINT IF EXISTS chk_artifact_key,
    DROP CONSTRAINT IF EXISTS chk_managed_artifact_source;

ALTER TABLE workflow.managed_artifact
    ADD CONSTRAINT chk_artifact_type CHECK (artifact_type IN ('pyspark', 'spark-jar')),
    ADD CONSTRAINT chk_artifact_key CHECK (artifact_key ~ '^[a-z0-9][a-z0-9_-]{0,63}[.](py|jar)$');

ALTER TABLE workflow.managed_artifact
    ADD CONSTRAINT chk_managed_artifact_source CHECK (
        (repository_id IS NULL AND commit_sha IS NULL AND source_path IS NULL)
        OR (repository_id IS NOT NULL AND commit_sha ~ '^[0-9a-f]{40}$'
            AND source_path ~ '^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*[.](py|jar)$'
            AND source_path !~ '(^|/)[.]{1,2}(/|$)')
    );

ALTER TABLE workflow.spark_job_definition
    ADD COLUMN IF NOT EXISTS main_class VARCHAR(256);

ALTER TABLE workflow.spark_job_definition
    ADD CONSTRAINT chk_spark_job_main_class CHECK (
        main_class IS NULL OR main_class ~ '^[A-Za-z_$][A-Za-z0-9_$.]{0,255}$'
    );
