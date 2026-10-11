-- 数据集进入显式发布生命周期。历史 active 记录代表已经投入使用的资产，
-- 一次性归档为 published；之后新建/修改的记录默认 draft，必须显式发布后才能查询。
ALTER TABLE serving.bi_dataset
    ADD COLUMN IF NOT EXISTS published_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS published_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;

UPDATE serving.bi_dataset
   SET status = 'published',
       published_version = CASE WHEN published_version > 0 THEN published_version ELSE 1 END,
       published_by = COALESCE(published_by, created_by),
       published_at = COALESCE(published_at, updated_at, now())
 WHERE status = 'active';

ALTER TABLE serving.bi_dataset ALTER COLUMN status SET DEFAULT 'draft';
ALTER TABLE serving.bi_dataset
    ADD CONSTRAINT chk_bi_dataset_status
    CHECK (status IN ('draft', 'published', 'deprecated'));
