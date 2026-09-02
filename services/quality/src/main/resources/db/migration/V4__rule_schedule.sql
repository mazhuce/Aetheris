-- 质量规则定时调度字段。
ALTER TABLE quality_rule
    ADD COLUMN IF NOT EXISTS cron_expression VARCHAR(64);
