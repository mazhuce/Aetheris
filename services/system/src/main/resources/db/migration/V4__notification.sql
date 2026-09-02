-- 平台通知：质量检查失败、接入/任务执行失败等事件的待办来源。
-- recipient 为目标用户名；当前由失败方服务写入并指向管理员，
-- 后续可按订阅关系扩展。
CREATE TABLE IF NOT EXISTS notification (
    id          BIGSERIAL PRIMARY KEY,
    recipient   VARCHAR(64)  NOT NULL,
    source      VARCHAR(32)  NOT NULL,
    source_id   BIGINT,
    level       VARCHAR(16)  NOT NULL DEFAULT 'info',
    title       VARCHAR(256) NOT NULL,
    message     VARCHAR(1024),
    link_path   VARCHAR(256),
    is_read     BOOL         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notification_recipient
    ON notification (recipient, is_read, id DESC);
