-- 调度同步补偿记录：任务定义入库成功但 Quartz 暂时不可用时，保留可重试的事实。
--
-- 不对 task 建外键：删除任务后仍需保留失败记录用于审计，且删除补偿本身
-- 需要能够处理 task 已不存在的情况。
CREATE TABLE IF NOT EXISTS scheduler_sync_outbox (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT       NOT NULL,
    operation       VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      VARCHAR(1024),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_scheduler_sync_operation CHECK (operation IN ('sync', 'unschedule')),
    CONSTRAINT chk_scheduler_sync_status CHECK (status IN ('pending', 'processing', 'succeeded', 'failed')),
    CONSTRAINT chk_scheduler_sync_attempts CHECK (attempts >= 0)
);

-- 同一任务同一操作只保留一个待处理项；新的失败会更新已有项，避免故障期间无限堆积。
CREATE UNIQUE INDEX IF NOT EXISTS uk_scheduler_sync_pending
    ON scheduler_sync_outbox (task_id, operation)
    WHERE status IN ('pending', 'processing');
CREATE INDEX IF NOT EXISTS idx_scheduler_sync_due
    ON scheduler_sync_outbox (status, next_attempt_at, id);
