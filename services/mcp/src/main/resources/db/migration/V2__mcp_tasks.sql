-- M3/M4：MCP 长任务与审批关联。高风险工具只创建审批单，任务状态可追溯。
CREATE TABLE IF NOT EXISTS mcp.mcp_task (
    id          BIGSERIAL PRIMARY KEY,
    uid         BIGINT NOT NULL,
    username    VARCHAR(120) NOT NULL,
    type        VARCHAR(48) NOT NULL,
    status      VARCHAR(32) NOT NULL,
    approval_id BIGINT,
    lab_code    VARCHAR(64),
    payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
    result      JSONB NOT NULL DEFAULT '{}'::jsonb,
    error       VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mcp_task_uid ON mcp.mcp_task (uid, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_mcp_task_approval ON mcp.mcp_task (approval_id);
