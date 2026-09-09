-- M2/M3: one managed Spark backend plus mixed SQL/Spark/quality pipeline evidence.

CREATE TABLE compute_environment (
    id                 BIGSERIAL PRIMARY KEY,
    environment_key    VARCHAR(64)  NOT NULL UNIQUE,
    name               VARCHAR(128) NOT NULL,
    adapter_type       VARCHAR(32)  NOT NULL,
    spark_version      VARCHAR(32)  NOT NULL,
    java_version       VARCHAR(32)  NOT NULL,
    python_version     VARCHAR(32)  NOT NULL,
    master             VARCHAR(64)  NOT NULL,
    max_cores          INT          NOT NULL,
    max_memory_mb      INT          NOT NULL,
    max_timeout_seconds INT         NOT NULL,
    max_concurrency    INT          NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_compute_adapter CHECK (adapter_type IN ('managed_local_submit')),
    CONSTRAINT chk_compute_status CHECK (status IN ('active', 'disabled')),
    CONSTRAINT chk_compute_limits CHECK (
        max_cores BETWEEN 1 AND 64 AND max_memory_mb BETWEEN 512 AND 262144
        AND max_timeout_seconds BETWEEN 30 AND 86400 AND max_concurrency BETWEEN 1 AND 128
    )
);

CREATE TABLE managed_artifact (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    version         VARCHAR(32)  NOT NULL,
    artifact_type   VARCHAR(16)  NOT NULL DEFAULT 'pyspark',
    artifact_key    VARCHAR(64)  NOT NULL,
    sha256          VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'draft',
    created_by      VARCHAR(64)  NOT NULL,
    approved_by     VARCHAR(64),
    approved_at     TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_managed_artifact_version UNIQUE (name, version),
    CONSTRAINT chk_artifact_type CHECK (artifact_type IN ('pyspark')),
    CONSTRAINT chk_artifact_status CHECK (status IN ('draft', 'approved', 'revoked')),
    CONSTRAINT chk_artifact_key CHECK (artifact_key ~ '^[a-z0-9][a-z0-9_-]{0,63}[.]py$'),
    CONSTRAINT chk_artifact_sha CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_managed_artifact_status ON managed_artifact(status, updated_at DESC);

CREATE TABLE spark_job_definition (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    version            INT          NOT NULL DEFAULT 1,
    environment_id     BIGINT       NOT NULL REFERENCES compute_environment(id),
    artifact_id        BIGINT       NOT NULL REFERENCES managed_artifact(id),
    parameter_schema   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    default_parameters JSONB        NOT NULL DEFAULT '{}'::jsonb,
    input_tables       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    output_table       VARCHAR(256),
    output_uri         VARCHAR(512)  NOT NULL,
    write_mode         VARCHAR(32)   NOT NULL DEFAULT 'overwrite_partition',
    driver_cores       INT           NOT NULL DEFAULT 1,
    driver_memory_mb   INT           NOT NULL DEFAULT 768,
    timeout_seconds    INT           NOT NULL DEFAULT 1800,
    max_retries        INT           NOT NULL DEFAULT 0,
    status             VARCHAR(16)   NOT NULL DEFAULT 'draft',
    definition_digest  VARCHAR(64),
    owner              VARCHAR(64)   NOT NULL,
    description        VARCHAR(512),
    published_by       VARCHAR(64),
    published_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_spark_job_version UNIQUE (name, version),
    CONSTRAINT chk_spark_job_status CHECK (status IN ('draft', 'published', 'disabled')),
    CONSTRAINT chk_spark_write_mode CHECK (write_mode IN ('overwrite_partition')),
    CONSTRAINT chk_spark_resources CHECK (
        driver_cores BETWEEN 1 AND 2 AND driver_memory_mb BETWEEN 512 AND 2048
        AND timeout_seconds BETWEEN 30 AND 86400 AND max_retries BETWEEN 0 AND 3
    ),
    CONSTRAINT chk_spark_definition_digest CHECK (
        definition_digest IS NULL OR definition_digest ~ '^[0-9a-f]{64}$'
    )
);

CREATE INDEX idx_spark_job_status ON spark_job_definition(status, updated_at DESC);

ALTER TABLE task ALTER COLUMN datasource_id DROP NOT NULL;
ALTER TABLE task ALTER COLUMN task_sql DROP NOT NULL;
ALTER TABLE task ADD COLUMN job_definition_id BIGINT REFERENCES spark_job_definition(id);
ALTER TABLE task ADD COLUMN quality_rule_id BIGINT;
ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_type;
ALTER TABLE task ADD CONSTRAINT chk_task_type CHECK (task_type IN ('sql', 'spark', 'quality'));
ALTER TABLE task ADD CONSTRAINT chk_task_definition_by_type CHECK (
    (task_type = 'sql' AND datasource_id IS NOT NULL AND task_sql IS NOT NULL
        AND job_definition_id IS NULL AND quality_rule_id IS NULL)
    OR (task_type = 'spark' AND job_definition_id IS NOT NULL
        AND datasource_id IS NULL AND task_sql IS NULL AND quality_rule_id IS NULL)
    OR (task_type = 'quality' AND quality_rule_id IS NOT NULL
        AND datasource_id IS NULL AND task_sql IS NULL AND job_definition_id IS NULL)
);

ALTER TABLE execution_attempt ALTER COLUMN task_sql DROP NOT NULL;
ALTER TABLE execution_attempt ALTER COLUMN datasource_id DROP NOT NULL;
ALTER TABLE execution_attempt
    ADD COLUMN task_type VARCHAR(16) NOT NULL DEFAULT 'sql',
    ADD COLUMN job_definition_id BIGINT REFERENCES spark_job_definition(id),
    ADD COLUMN job_version INT,
    ADD COLUMN quality_rule_id BIGINT,
    ADD COLUMN input_partitions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN output_partitions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN result_metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN log_cursor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cancellation_confirmed_at TIMESTAMPTZ;
ALTER TABLE execution_attempt ADD CONSTRAINT chk_attempt_task_type
    CHECK (task_type IN ('sql', 'spark', 'quality'));

CREATE TABLE execution_event (
    id             BIGSERIAL PRIMARY KEY,
    attempt_id     BIGINT       NOT NULL REFERENCES execution_attempt(id) ON DELETE CASCADE,
    event_type     VARCHAR(32)  NOT NULL,
    from_status    VARCHAR(24),
    to_status      VARCHAR(24),
    message        VARCHAR(512),
    details_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_execution_event_attempt ON execution_event(attempt_id, id);

CREATE TABLE pipeline_run (
    id             BIGSERIAL PRIMARY KEY,
    target_task_id BIGINT       NOT NULL REFERENCES task(id),
    biz_date       VARCHAR(10)  NOT NULL,
    status         VARCHAR(24)  NOT NULL DEFAULT 'queued',
    triggered_by   VARCHAR(64)  NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    error_message  VARCHAR(1024),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_pipeline_status CHECK (
        status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled', 'unknown')
    )
);
CREATE UNIQUE INDEX uq_pipeline_active_target_date ON pipeline_run(target_task_id, biz_date)
    WHERE status IN ('queued', 'running');

CREATE TABLE pipeline_node_run (
    id             BIGSERIAL PRIMARY KEY,
    pipeline_run_id BIGINT      NOT NULL REFERENCES pipeline_run(id) ON DELETE CASCADE,
    task_id        BIGINT       NOT NULL REFERENCES task(id),
    sequence_no    INT          NOT NULL,
    status         VARCHAR(24)  NOT NULL DEFAULT 'queued',
    instance_id    BIGINT REFERENCES task_instance(id),
    waiver_id      BIGINT,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    message        VARCHAR(512),
    CONSTRAINT uk_pipeline_node UNIQUE (pipeline_run_id, task_id),
    CONSTRAINT chk_pipeline_node_status CHECK (
        status IN ('queued', 'running', 'succeeded', 'failed', 'skipped', 'waived', 'unknown')
    )
);
CREATE INDEX idx_pipeline_node_run ON pipeline_node_run(pipeline_run_id, sequence_no);

CREATE TABLE quality_waiver (
    id              BIGSERIAL PRIMARY KEY,
    quality_task_id BIGINT       NOT NULL REFERENCES task(id),
    biz_date        VARCHAR(10)  NOT NULL,
    reason          VARCHAR(512) NOT NULL,
    approved_by     VARCHAR(64)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_quality_waiver UNIQUE (quality_task_id, biz_date),
    CONSTRAINT chk_quality_waiver_expiry CHECK (expires_at > created_at)
);
ALTER TABLE pipeline_node_run ADD CONSTRAINT fk_pipeline_node_waiver
    FOREIGN KEY (waiver_id) REFERENCES quality_waiver(id);

ALTER TABLE release_record
    ADD COLUMN target_ref_id BIGINT,
    ADD COLUMN snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN snapshot_digest VARCHAR(64),
    ADD COLUMN approved_digest VARCHAR(64),
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN quality_rule_id BIGINT,
    ADD COLUMN quality_result_id BIGINT,
    ADD COLUMN impact_summary JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE release_record ADD CONSTRAINT chk_release_snapshot_digest CHECK (
    snapshot_digest IS NULL OR snapshot_digest ~ '^[0-9a-f]{64}$'
);
ALTER TABLE release_record ADD CONSTRAINT chk_release_approved_digest CHECK (
    approved_digest IS NULL OR approved_digest ~ '^[0-9a-f]{64}$'
);

INSERT INTO compute_environment
    (environment_key, name, adapter_type, spark_version, java_version, python_version,
     master, max_cores, max_memory_mb, max_timeout_seconds, max_concurrency)
VALUES
    ('wsl-local-spark-3.5.6', 'WSL 本机受管 Spark 3.5.6', 'managed_local_submit',
     '3.5.6', '11', '3.12', 'local', 2, 2048, 86400, 1)
ON CONFLICT (environment_key) DO NOTHING;

INSERT INTO managed_artifact
    (name, version, artifact_type, artifact_key, sha256, status,
     created_by, approved_by, approved_at)
VALUES
    ('订单日汇总受管模板', '1.0.0', 'pyspark', 'order_daily_profile.py',
     '736d5e39332606e2a1feef98fb4a2bceb76c80eaf6467261d822f89cd5034084',
     'approved', 'system', 'system', now())
ON CONFLICT (name, version) DO NOTHING;

INSERT INTO spark_job_definition
    (name, version, environment_id, artifact_id, parameter_schema, default_parameters,
     input_tables, output_table, output_uri, write_mode, driver_cores, driver_memory_mb,
     timeout_seconds, max_retries, status, definition_digest, owner, description,
     published_by, published_at)
SELECT
    '订单日汇总受管作业', 1, environment.id, artifact.id,
    '{"bizDate":{"type":"date","required":true}}'::jsonb, '{}'::jsonb,
    '["hive_prod.dwd.dwd_order"]'::jsonb, NULL,
    'hdfs://localhost:9000/user/aetheris/managed/order_daily_profile',
    'overwrite_partition', 1, 768, 1800, 0, 'published',
    '44c559a21dd91e5e3020cb16633033a263c17e6a1832781dc8c0e6dab5ad1192',
    'system', '固定读取 DWD 订单并写入隔离 HDFS 分区的首个受管 PySpark 模板。',
    'system', now()
FROM compute_environment environment
JOIN managed_artifact artifact ON artifact.name = '订单日汇总受管模板' AND artifact.version = '1.0.0'
WHERE environment.environment_key = 'wsl-local-spark-3.5.6'
ON CONFLICT (name, version) DO NOTHING;
