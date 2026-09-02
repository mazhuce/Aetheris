-- 标签资产：为数据表打业务标签（如「核心表」「敏感」「待归档」）。
-- 标签本身为自由文本，按名称分组聚合展示；同一表可挂多个标签，同一标签可挂多张表。
CREATE TABLE table_tag (
    id                BIGSERIAL PRIMARY KEY,
    metadata_table_id BIGINT      NOT NULL,
    tag_name          VARCHAR(64) NOT NULL,
    created_by        VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_table_tag UNIQUE (metadata_table_id, tag_name)
);

CREATE INDEX idx_table_tag_name ON table_tag (tag_name);
