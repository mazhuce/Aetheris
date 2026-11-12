-- 内部 WORKLOAD 请求的持久化防重放表（mcp-server → learning-service）。
-- 与业务执行凭证分开，nonce 过期可清理，业务证据不在此删除。
CREATE TABLE IF NOT EXISTS learning.internal_request_nonce (
    caller     VARCHAR(64) NOT NULL,
    nonce      VARCHAR(86) NOT NULL,
    expires_at BIGINT NOT NULL CHECK (expires_at > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (caller, nonce)
);
CREATE INDEX IF NOT EXISTS internal_request_nonce_expiry_idx
    ON learning.internal_request_nonce (expires_at);
