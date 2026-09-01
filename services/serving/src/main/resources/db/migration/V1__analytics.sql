CREATE TABLE IF NOT EXISTS bi_dataset (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(512),
    datasource_id    BIGINT NOT NULL,
    datasource_name  VARCHAR(128),
    query_sql        TEXT NOT NULL,
    schema_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    refresh_seconds  INTEGER NOT NULL DEFAULT 30 CHECK (refresh_seconds BETWEEN 5 AND 3600),
    status           VARCHAR(16) NOT NULL DEFAULT 'active',
    created_by       VARCHAR(128) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS bi_dashboard_template (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(64) NOT NULL UNIQUE,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    category      VARCHAR(64) NOT NULL,
    preview_tone  VARCHAR(32) NOT NULL DEFAULT 'neutral',
    spec_json     JSONB NOT NULL,
    sort_order    INTEGER NOT NULL DEFAULT 0,
    enabled       BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS bi_dashboard (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    description        VARCHAR(512),
    slug               VARCHAR(96) NOT NULL UNIQUE,
    status             VARCHAR(16) NOT NULL DEFAULT 'draft',
    draft_spec         JSONB NOT NULL,
    published_spec     JSONB,
    published_version  INTEGER NOT NULL DEFAULT 0,
    created_by         VARCHAR(128) NOT NULL,
    updated_by         VARCHAR(128) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at       TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS bi_dashboard_version (
    id            BIGSERIAL PRIMARY KEY,
    dashboard_id  BIGINT NOT NULL REFERENCES bi_dashboard(id) ON DELETE CASCADE,
    version_no    INTEGER NOT NULL,
    spec_json     JSONB NOT NULL,
    created_by    VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dashboard_id, version_no)
);

INSERT INTO bi_dashboard_template (code, name, description, category, preview_tone, spec_json, sort_order)
VALUES
('blank', '空白画布', '从一张干净的网格开始，自由组织指标与图表。', '基础', 'graphite',
 '{"schemaVersion":1,"theme":"dark","layouts":{"lg":[]},"widgets":[],"globalFilters":[]}'::jsonb, 0),
('executive', '经营概览', '适合管理层总览，包含核心指标、趋势、结构与排名。', '经营分析', 'silver',
 '{"schemaVersion":1,"theme":"dark","layouts":{"lg":[{"i":"metric-1","x":0,"y":0,"w":6,"h":3},{"i":"metric-2","x":6,"y":0,"w":6,"h":3},{"i":"metric-3","x":12,"y":0,"w":6,"h":3},{"i":"metric-4","x":18,"y":0,"w":6,"h":3},{"i":"trend-1","x":0,"y":3,"w":15,"h":8},{"i":"pie-1","x":15,"y":3,"w":9,"h":8},{"i":"bar-1","x":0,"y":11,"w":24,"h":8}]},"widgets":[{"id":"metric-1","type":"metric","title":"核心指标一","mapping":{}},{"id":"metric-2","type":"metric","title":"核心指标二","mapping":{}},{"id":"metric-3","type":"metric","title":"核心指标三","mapping":{}},{"id":"metric-4","type":"metric","title":"核心指标四","mapping":{}},{"id":"trend-1","type":"line","title":"趋势分析","mapping":{}},{"id":"pie-1","type":"pie","title":"结构占比","mapping":{}},{"id":"bar-1","type":"bar","title":"分类排名","mapping":{}}],"globalFilters":[]}'::jsonb, 10),
('quality', '数据质量驾驶舱', '集中呈现质量通过率、问题趋势和异常分布。', '数据治理', 'emerald',
 '{"schemaVersion":1,"theme":"dark","layouts":{"lg":[{"i":"quality-rate","x":0,"y":0,"w":8,"h":4},{"i":"issue-count","x":8,"y":0,"w":8,"h":4},{"i":"covered-tables","x":16,"y":0,"w":8,"h":4},{"i":"quality-trend","x":0,"y":4,"w":16,"h":8},{"i":"issue-pie","x":16,"y":4,"w":8,"h":8},{"i":"issue-table","x":0,"y":12,"w":24,"h":8}]},"widgets":[{"id":"quality-rate","type":"metric","title":"规则通过率","mapping":{}},{"id":"issue-count","type":"metric","title":"治理问题","mapping":{}},{"id":"covered-tables","type":"metric","title":"覆盖数据表","mapping":{}},{"id":"quality-trend","type":"line","title":"质量趋势","mapping":{}},{"id":"issue-pie","type":"pie","title":"问题分布","mapping":{}},{"id":"issue-table","type":"table","title":"异常明细","mapping":{}}],"globalFilters":[]}'::jsonb, 20),
('realtime', '实时运营监控', '面向分钟级指标变化的实时态势看板。', '实时监控', 'amber',
 '{"schemaVersion":1,"theme":"dark","layouts":{"lg":[{"i":"live-1","x":0,"y":0,"w":6,"h":4},{"i":"live-2","x":6,"y":0,"w":6,"h":4},{"i":"live-3","x":12,"y":0,"w":6,"h":4},{"i":"live-4","x":18,"y":0,"w":6,"h":4},{"i":"live-trend","x":0,"y":4,"w":24,"h":9},{"i":"live-bar","x":0,"y":13,"w":14,"h":8},{"i":"live-table","x":14,"y":13,"w":10,"h":8}]},"widgets":[{"id":"live-1","type":"metric","title":"当前流量","mapping":{}},{"id":"live-2","type":"metric","title":"处理成功率","mapping":{}},{"id":"live-3","type":"metric","title":"平均延迟","mapping":{}},{"id":"live-4","type":"metric","title":"活跃任务","mapping":{}},{"id":"live-trend","type":"line","title":"实时趋势","mapping":{},"refreshSeconds":10},{"id":"live-bar","type":"bar","title":"来源分布","mapping":{},"refreshSeconds":10},{"id":"live-table","type":"table","title":"最新事件","mapping":{},"refreshSeconds":10}],"globalFilters":[]}'::jsonb, 30)
ON CONFLICT (code) DO NOTHING;
