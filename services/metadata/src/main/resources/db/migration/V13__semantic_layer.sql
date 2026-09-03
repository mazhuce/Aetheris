-- 企业语义层：正式业务域、维度、模型/指标发布快照及可执行指标绑定。

CREATE TABLE business_domain (
    id           BIGSERIAL PRIMARY KEY,
    domain_code  VARCHAR(64)  NOT NULL UNIQUE,
    domain_name  VARCHAR(128) NOT NULL UNIQUE,
    owner        VARCHAR(64),
    status       VARCHAR(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    description  VARCHAR(512),
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO business_domain (domain_code, domain_name, owner, description, created_by)
VALUES ('UNASSIGNED', '未归属域', 'admin', '尚未完成业务域归属的数据资产', 'system')
ON CONFLICT (domain_code) DO NOTHING;

INSERT INTO business_domain (domain_code, domain_name, owner, description, created_by)
SELECT 'DOMAIN_' || substr(md5(domain_name), 1, 12), domain_name, 'admin', '由历史元数据自动迁移', 'system'
FROM (
    SELECT DISTINCT trim(domain) AS domain_name FROM metadata_table WHERE nullif(trim(domain), '') IS NOT NULL
    UNION
    SELECT DISTINCT trim(business_domain) FROM metric_definition WHERE nullif(trim(business_domain), '') IS NOT NULL
    UNION
    SELECT DISTINCT trim(business_domain) FROM logical_model WHERE nullif(trim(business_domain), '') IS NOT NULL
) migrated
ON CONFLICT (domain_name) DO NOTHING;

ALTER TABLE metadata_table ADD COLUMN business_domain_id BIGINT REFERENCES business_domain(id) ON DELETE SET NULL;
UPDATE metadata_table t
SET business_domain_id = d.id
FROM business_domain d
WHERE t.business_domain_id IS NULL AND d.domain_name = t.domain;
CREATE INDEX idx_metadata_table_business_domain ON metadata_table (business_domain_id);

ALTER TABLE logical_model
    ADD COLUMN business_domain_id BIGINT REFERENCES business_domain(id) ON DELETE SET NULL,
    ADD COLUMN published_version INTEGER CHECK (published_version IS NULL OR (published_version > 0 AND published_version <= version)),
    ADD COLUMN published_by VARCHAR(64),
    ADD COLUMN published_at TIMESTAMPTZ;
UPDATE logical_model m
SET business_domain_id = d.id
FROM business_domain d
WHERE m.business_domain_id IS NULL AND d.domain_name = m.business_domain;

-- 旧结构是实体数组；统一升级为显式 entities + relations 聚合根。
UPDATE logical_model
SET entities_json = jsonb_build_object('entities', entities_json, 'relations', '[]'::jsonb)
WHERE jsonb_typeof(entities_json) = 'array';

CREATE TABLE logical_model_version (
    id               BIGSERIAL PRIMARY KEY,
    logical_model_id BIGINT NOT NULL REFERENCES logical_model(id) ON DELETE CASCADE,
    version_no       INTEGER NOT NULL,
    snapshot_json    JSONB NOT NULL,
    published_by     VARCHAR(64) NOT NULL,
    published_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (logical_model_id, version_no)
);

INSERT INTO logical_model_version (logical_model_id, version_no, snapshot_json, published_by, published_at)
SELECT id, version,
       jsonb_build_object(
           'modelCode', model_code, 'modelName', model_name, 'businessDomain', business_domain,
           'modelType', model_type, 'entities', entities_json, 'description', description, 'owner', owner),
       coalesce(created_by, 'system'), coalesce(updated_at, now())
FROM logical_model
WHERE status = 'published'
ON CONFLICT DO NOTHING;

-- 历史“已发布”记录缺少真实表绑定，保留旧快照后进入下一版草稿，避免被误当作可消费模型。
UPDATE logical_model
SET status = 'draft', version = version + 1, published_by = NULL, published_at = NULL, updated_at = now()
WHERE status = 'published';

CREATE TABLE semantic_dimension (
    id                 BIGSERIAL PRIMARY KEY,
    dimension_code     VARCHAR(64)  NOT NULL UNIQUE,
    dimension_name     VARCHAR(128) NOT NULL,
    business_domain_id BIGINT REFERENCES business_domain(id) ON DELETE SET NULL,
    metadata_table_id  BIGINT NOT NULL REFERENCES metadata_table(id) ON DELETE RESTRICT,
    column_name        VARCHAR(128) NOT NULL,
    dimension_type     VARCHAR(24) NOT NULL DEFAULT 'categorical'
        CHECK (dimension_type IN ('categorical', 'time', 'geography')),
    status             VARCHAR(16) NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'published', 'deprecated')),
    version            INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    published_version  INTEGER CHECK (published_version IS NULL OR (published_version > 0 AND published_version <= version)),
    owner              VARCHAR(64),
    description        VARCHAR(512),
    created_by         VARCHAR(64),
    published_by       VARCHAR(64),
    published_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_semantic_dimension_domain ON semantic_dimension (business_domain_id);
CREATE INDEX idx_semantic_dimension_table ON semantic_dimension (metadata_table_id);

CREATE TABLE semantic_dimension_version (
    id                    BIGSERIAL PRIMARY KEY,
    semantic_dimension_id BIGINT NOT NULL REFERENCES semantic_dimension(id) ON DELETE CASCADE,
    version_no            INTEGER NOT NULL,
    snapshot_json         JSONB NOT NULL,
    published_by          VARCHAR(64) NOT NULL,
    published_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (semantic_dimension_id, version_no)
);

ALTER TABLE metric_definition
    ADD COLUMN business_domain_id BIGINT REFERENCES business_domain(id) ON DELETE SET NULL,
    ADD COLUMN logical_model_id BIGINT REFERENCES logical_model(id) ON DELETE SET NULL,
    ADD COLUMN source_table_id BIGINT REFERENCES metadata_table(id) ON DELETE SET NULL,
    ADD COLUMN aggregation VARCHAR(24),
    ADD COLUMN measure_column VARCHAR(128),
    ADD COLUMN filters_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN time_column VARCHAR(128),
    ADD COLUMN default_time_grain VARCHAR(16)
        CHECK (default_time_grain IS NULL OR default_time_grain IN ('none', 'day', 'month', 'year')),
    ADD COLUMN dimension_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN published_version INTEGER CHECK (published_version IS NULL OR (published_version > 0 AND published_version <= version)),
    ADD COLUMN published_by VARCHAR(64),
    ADD COLUMN published_at TIMESTAMPTZ;
UPDATE metric_definition m
SET business_domain_id = d.id
FROM business_domain d
WHERE m.business_domain_id IS NULL AND d.domain_name = m.business_domain;

CREATE TABLE metric_definition_version (
    id                   BIGSERIAL PRIMARY KEY,
    metric_definition_id BIGINT NOT NULL REFERENCES metric_definition(id) ON DELETE CASCADE,
    version_no           INTEGER NOT NULL,
    snapshot_json        JSONB NOT NULL,
    published_by         VARCHAR(64) NOT NULL,
    published_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (metric_definition_id, version_no)
);

INSERT INTO metric_definition_version (metric_definition_id, version_no, snapshot_json, published_by, published_at)
SELECT id, version,
       jsonb_build_object(
           'metricCode', metric_code, 'metricName', metric_name, 'businessDomain', business_domain,
           'layer', layer, 'calibreFormula', calibre_formula, 'unit', unit,
           'description', description, 'owner', owner),
       coalesce(created_by, 'system'), coalesce(updated_at, now())
FROM metric_definition
WHERE status = 'published'
ON CONFLICT DO NOTHING;

-- 历史指标只有说明性公式，尚不能由语义查询引擎安全执行；保留旧快照并转为待补全草稿。
UPDATE metric_definition
SET status = 'draft', version = version + 1, published_by = NULL, published_at = NULL, updated_at = now()
WHERE status = 'published' AND source_table_id IS NULL;

CREATE INDEX idx_metric_definition_source_table ON metric_definition (source_table_id);
CREATE INDEX idx_metric_definition_logical_model ON metric_definition (logical_model_id);
