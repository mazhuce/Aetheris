-- 同一学员复用固定 schema 名：历史 dropped 行必须允许保留，
-- 唯一性只约束当前 active 的沙箱，否则重置/过期恢复后无法重建。
ALTER TABLE learning.sandbox_instance DROP CONSTRAINT IF EXISTS sandbox_instance_schema_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_sandbox_active_schema
    ON learning.sandbox_instance (schema_name)
    WHERE status = 'active';