-- 任务编排表：任务定义、运行实例、日志与依赖。
--
-- 设计要点：
--   1. 幂等：(task_id, biz_date) 唯一，同一业务日期只有一个实例，重跑复用该行；
--      配合 INSERT OVERWRITE 覆盖分区，重跑不会产生重复数据。
--   2. 定义与运行分离：task 存「怎么跑」，task_instance 存「跑得如何」，
--      历史实例不受任务定义修改影响。
--   3. 日志独立成表：单次执行的日志可能较长，与实例表分开避免行膨胀影响列表查询。

CREATE TABLE IF NOT EXISTS task (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    -- 当前只实现 sql 一种；预留字段以便后续扩展 shell / api 等类型。
    task_type       VARCHAR(32)  NOT NULL DEFAULT 'sql',
    -- 目标数据源在 metadata.data_source 中的主键。刻意不做外键：
    -- 跨 schema 的服务边界应由应用层校验，避免数据库层耦合两个服务的发布顺序。
    datasource_id   BIGINT       NOT NULL,
    datasource_name VARCHAR(128),
    -- 加工语句，执行前由 metadata 侧的 SqlWriteGuard 白名单校验。
    task_sql        TEXT         NOT NULL,
    -- Quartz cron 表达式（6 或 7 段）。为空表示仅支持手动触发。
    cron_expression VARCHAR(64),
    -- 目标表，用于展示与血缘关联；由 SQL 解析得出，也可人工修正。
    target_table    VARCHAR(256),
    timeout_seconds INT          NOT NULL DEFAULT 1800,
    max_retries     INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'enabled',
    owner           VARCHAR(64),
    description     VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_type   CHECK (task_type IN ('sql')),
    CONSTRAINT chk_task_status CHECK (status IN ('enabled', 'disabled')),
    CONSTRAINT chk_task_timeout CHECK (timeout_seconds BETWEEN 1 AND 86400),
    CONSTRAINT chk_task_retries CHECK (max_retries BETWEEN 0 AND 10)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_task_name ON task (name);
CREATE INDEX IF NOT EXISTS idx_task_datasource ON task (datasource_id);

CREATE TABLE IF NOT EXISTS task_instance (
    id             BIGSERIAL PRIMARY KEY,
    task_id        BIGINT       NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    -- 业务日期（数据日期），与分区键 dt 对应，不是执行时刻。
    biz_date       VARCHAR(10)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'pending',
    -- manual=人工触发，schedule=cron 触发，retry=失败重试
    trigger_type   VARCHAR(16)  NOT NULL DEFAULT 'manual',
    attempt        INT          NOT NULL DEFAULT 1,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    duration_ms    BIGINT,
    affected_rows  BIGINT,
    -- 失败原因摘要；完整堆栈在 task_log 中。
    error_message  VARCHAR(1024),
    triggered_by   VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_instance_status
        CHECK (status IN ('pending', 'running', 'success', 'failed', 'skipped')),
    CONSTRAINT chk_task_instance_trigger
        CHECK (trigger_type IN ('manual', 'schedule', 'retry'))
);

-- 幂等的核心：同一任务同一业务日期只保留一个实例。
-- 重跑时更新该行而非插入新行，因此实例列表不会因反复重跑而膨胀，
-- 也避免了「同一天的数据被两个并发实例同时写入」。
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_instance_biz
    ON task_instance (task_id, biz_date);
CREATE INDEX IF NOT EXISTS idx_task_instance_status ON task_instance (status);
CREATE INDEX IF NOT EXISTS idx_task_instance_task   ON task_instance (task_id, biz_date DESC);

CREATE TABLE IF NOT EXISTS task_log (
    id          BIGSERIAL PRIMARY KEY,
    instance_id BIGINT      NOT NULL REFERENCES task_instance (id) ON DELETE CASCADE,
    level       VARCHAR(16) NOT NULL DEFAULT 'info',
    message     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_log_level CHECK (level IN ('info', 'warn', 'error'))
);

CREATE INDEX IF NOT EXISTS idx_task_log_instance ON task_log (instance_id, id);

CREATE TABLE IF NOT EXISTS task_dependency (
    id                 BIGSERIAL PRIMARY KEY,
    task_id            BIGINT      NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    upstream_task_id   BIGINT      NOT NULL REFERENCES task (id) ON DELETE CASCADE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 同一条依赖边只登记一次。
    CONSTRAINT uk_task_dependency UNIQUE (task_id, upstream_task_id),
    -- 自依赖会让拓扑判定永远无法满足，直接在库层拒绝。
    CONSTRAINT chk_task_dependency_not_self CHECK (task_id <> upstream_task_id)
);

CREATE INDEX IF NOT EXISTS idx_task_dependency_upstream ON task_dependency (upstream_task_id);
