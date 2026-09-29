-- 将 API 资产从登记台账推进为受控的数据服务执行契约。
ALTER TABLE api_asset
    ADD COLUMN target_type VARCHAR(16),
    ADD COLUMN target_ref VARCHAR(128),
    ADD COLUMN published_by VARCHAR(64),
    ADD COLUMN published_at TIMESTAMPTZ;

-- 历史样例没有执行目标，不能继续伪装成可调用的 published API。
UPDATE api_asset
SET status = 'registered', updated_at = now()
WHERE status = 'published' AND target_type IS NULL;

ALTER TABLE api_asset
    ADD CONSTRAINT ck_api_asset_target_type
        CHECK (target_type IS NULL OR target_type IN ('metric', 'table')),
    ADD CONSTRAINT ck_api_asset_published_target
        CHECK (status <> 'published' OR (target_type IS NOT NULL AND target_ref IS NOT NULL));

CREATE INDEX idx_api_asset_published_target
    ON api_asset (status, target_type, target_ref);
