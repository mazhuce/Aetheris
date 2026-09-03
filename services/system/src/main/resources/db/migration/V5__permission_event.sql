-- 权限审计事件：授权、撤销、拒绝、越权尝试与权限服务不可用。
-- 来源：auth-service 的权限决策引擎与管理操作（PermissionAuditClient 投递）。
-- 只记录主体与决策要素，不含密码、JWT、API Key 等敏感信息。
CREATE TABLE IF NOT EXISTS permission_event (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64),
    user_id       BIGINT,
    resource_type VARCHAR(64),
    resource_id   VARCHAR(64),
    action        VARCHAR(64),
    -- ALLOWED / DENIED / GRANTED / REVOKED / UPDATED / DELETED / UNAVAILABLE
    decision      VARCHAR(32)  NOT NULL,
    reason        VARCHAR(512),
    request_id    VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_permission_event_created_at
    ON permission_event (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_permission_event_username
    ON permission_event (username, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_permission_event_decision
    ON permission_event (decision, created_at DESC);