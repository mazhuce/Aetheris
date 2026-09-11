-- M4: durable multi-runner admission. Workflow replicas coordinate through
-- row locks, so runner concurrency, CPU and memory cannot be oversubscribed.

CREATE TABLE compute_runner (
    id                 BIGSERIAL PRIMARY KEY,
    environment_id     BIGINT       NOT NULL REFERENCES compute_environment(id),
    runner_key         VARCHAR(64)  NOT NULL UNIQUE,
    name               VARCHAR(128) NOT NULL,
    base_url           VARCHAR(512) NOT NULL UNIQUE,
    max_cores          INT          NOT NULL,
    max_memory_mb      INT          NOT NULL,
    max_concurrency    INT          NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_by         VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_compute_runner_status CHECK (status IN ('active','draining','disabled')),
    CONSTRAINT chk_compute_runner_capacity CHECK (
        max_cores BETWEEN 1 AND 256 AND max_memory_mb BETWEEN 512 AND 1048576
        AND max_concurrency BETWEEN 1 AND 256
    ),
    CONSTRAINT chk_compute_runner_url CHECK (
        base_url = 'config://default'
        OR base_url ~ '^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?/?$'
    )
);

CREATE INDEX idx_compute_runner_environment
    ON compute_runner(environment_id, status, id);

ALTER TABLE execution_attempt
    ADD COLUMN runner_id BIGINT REFERENCES compute_runner(id),
    ADD COLUMN admitted_at TIMESTAMPTZ;

CREATE TABLE spark_resource_allocation (
    id                  BIGSERIAL PRIMARY KEY,
    attempt_id          BIGINT      NOT NULL UNIQUE REFERENCES execution_attempt(id) ON DELETE CASCADE,
    environment_id      BIGINT      NOT NULL REFERENCES compute_environment(id),
    runner_id           BIGINT REFERENCES compute_runner(id),
    requested_cores     INT         NOT NULL,
    requested_memory_mb INT         NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'queued',
    queued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    admitted_at         TIMESTAMPTZ,
    released_at         TIMESTAMPTZ,
    release_reason      VARCHAR(128),
    CONSTRAINT chk_spark_allocation_status CHECK (status IN ('queued','admitted','released')),
    CONSTRAINT chk_spark_allocation_request CHECK (
        requested_cores BETWEEN 1 AND 256 AND requested_memory_mb BETWEEN 512 AND 1048576
    ),
    CONSTRAINT chk_spark_allocation_lifecycle CHECK (
        (status='queued' AND runner_id IS NULL AND admitted_at IS NULL AND released_at IS NULL)
        OR (status='admitted' AND runner_id IS NOT NULL AND admitted_at IS NOT NULL AND released_at IS NULL)
        OR (status='released' AND released_at IS NOT NULL)
    )
);

CREATE INDEX idx_spark_allocation_queue
    ON spark_resource_allocation(environment_id, queued_at, id)
    WHERE status='queued';
CREATE INDEX idx_spark_allocation_capacity
    ON spark_resource_allocation(runner_id)
    WHERE status='admitted';

INSERT INTO compute_runner
    (environment_id,runner_key,name,base_url,max_cores,max_memory_mb,max_concurrency,status,created_by)
SELECT id,'default-local-runner','默认本机 Spark Runner','config://default',
       max_cores,max_memory_mb,max_concurrency,'active','system'
FROM compute_environment
WHERE environment_key='wsl-local-spark-3.5.6'
ON CONFLICT (runner_key) DO NOTHING;
