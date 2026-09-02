-- B 档 CRUD 实体：指标定义 / 逻辑模型 / API 资产 / 数据产品。
-- 模块间通过 *_code 唯一编码互相引用与对外检索，均带 version 与 status 管理生命周期。

-- 指标定义：指标的口径、层级（原子/派生/复合）与负责人登记。
CREATE TABLE metric_definition (
    id               BIGSERIAL PRIMARY KEY,
    metric_code      VARCHAR(64)  NOT NULL,
    metric_name      VARCHAR(128) NOT NULL,
    business_domain  VARCHAR(64),
    layer            VARCHAR(16),
    calibre_formula  TEXT,
    unit             VARCHAR(32),
    owner            VARCHAR(64),
    status           VARCHAR(16)  NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','deprecated')),
    version          INTEGER      NOT NULL DEFAULT 1,
    description      VARCHAR(512),
    created_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_metric_definition_code UNIQUE (metric_code)
);

CREATE INDEX idx_metric_definition_domain ON metric_definition (business_domain);

INSERT INTO metric_definition (metric_code, metric_name, business_domain, layer, calibre_formula, unit, owner, status, version, description, created_by)
VALUES
    ('GMV_DAILY', '日成交总额', '交易域', 'atomic', 'SUM(pay_amount) WHERE pay_status = ''SUCCESS''', '元', '李明', 'published', 3, '当日支付成功订单的金额总和', 'admin'),
    ('ORDER_CVR', '下单转化率', '交易域', 'derived', '下单用户数 / 访问用户数 * 100', '%', '王芳', 'published', 2, '衡量流量到下单的转化效率', 'admin'),
    ('ARPU_30D', '近30日人均贡献收入', '用户域', 'composite', '近30日订单总额 / 近30日活跃用户数', '元', '张伟', 'draft', 1, '评估活跃用户的平均营收贡献，口径待评审', 'admin'),
    ('REFUND_RATE_OLD', '退款率（旧口径）', '售后域', 'derived', '退款订单数 / 总订单数 * 100', '%', '李明', 'deprecated', 4, '旧口径，已由按金额口径的 REFUND_RATE_AMT 替代', 'admin')
ON CONFLICT DO NOTHING;

-- 逻辑模型：业务域的实体与关系建模（conceptual/relational/dimensional）。
CREATE TABLE logical_model (
    id               BIGSERIAL PRIMARY KEY,
    model_code       VARCHAR(64)  NOT NULL,
    model_name       VARCHAR(128) NOT NULL,
    business_domain  VARCHAR(64),
    model_type       VARCHAR(32)  NOT NULL DEFAULT 'relational',
    entities_json    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status           VARCHAR(16)  NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','deprecated')),
    version          INTEGER      NOT NULL DEFAULT 1,
    description      VARCHAR(512),
    owner            VARCHAR(64),
    created_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_logical_model_code UNIQUE (model_code)
);

CREATE INDEX idx_logical_model_domain ON logical_model (business_domain);

INSERT INTO logical_model (model_code, model_name, business_domain, model_type, entities_json, status, version, description, owner, created_by)
VALUES
    ('TRD_REL_V1', '交易域关系模型', '交易域', 'relational',
     '[{"entity":"客户","relations":[{"to":"订单","type":"1:N"}]},{"entity":"订单","relations":[{"to":"订单明细","type":"1:N"},{"to":"支付流水","type":"1:N"}]}]',
     'published', 2, '客户-订单-订单明细-支付流水四实体关系建模，支撑交易域指标加工', '王芳', 'admin'),
    ('USR_REL_V1', '用户域关系模型', '用户域', 'relational',
     '[{"entity":"用户","relations":[{"to":"用户标签","type":"1:N"}]},{"entity":"设备","relations":[{"to":"用户","type":"N:1"}]}]',
     'published', 1, '用户与设备、标签的关系建模，支撑用户画像类应用', '张伟', 'admin'),
    ('SALE_DIM_V1', '销售分析维度模型', '销售域', 'dimensional',
     '[{"entity":"销售事实表","relations":[{"to":"日期维度","type":"N:1"},{"to":"商品维度","type":"N:1"},{"to":"门店维度","type":"N:1"}]}]',
     'draft', 1, '按星型模型组织销售事实与日期/商品/门店维度', '李明', 'admin'),
    ('ORG_CONCEPT_V1', '组织架构概念模型', '人事域', 'conceptual',
     '[{"entity":"部门","relations":[{"to":"员工","type":"1:N"}]},{"entity":"员工","relations":[{"to":"岗位","type":"N:1"}]}]',
     'draft', 1, '梳理部门-员工-岗位的核心概念关系，尚未细化到字段', '张伟', 'admin')
ON CONFLICT DO NOTHING;

-- API 资产登记：对外暴露的数据服务接口台账。
CREATE TABLE api_asset (
    id               BIGSERIAL PRIMARY KEY,
    asset_code       VARCHAR(64)  NOT NULL,
    asset_name       VARCHAR(128) NOT NULL,
    api_path         VARCHAR(256) NOT NULL,
    http_method      VARCHAR(8)   NOT NULL DEFAULT 'GET',
    category         VARCHAR(64),
    owner            VARCHAR(64),
    status           VARCHAR(16)  NOT NULL DEFAULT 'registered' CHECK (status IN ('registered','published','deprecated')),
    version          VARCHAR(32)  NOT NULL DEFAULT 'v1',
    description      VARCHAR(512),
    created_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_api_asset_code UNIQUE (asset_code)
);

CREATE INDEX idx_api_asset_category ON api_asset (category);

INSERT INTO api_asset (asset_code, asset_name, api_path, http_method, category, owner, status, version, description, created_by)
VALUES
    ('API_ORDER_DETAIL', '订单详情查询', '/api/serving/orders/{id}', 'GET', '交易服务', '张伟', 'published', 'v2', '按订单 ID 查询订单全量信息，含明细与支付状态', 'admin'),
    ('API_USER_PROFILE', '用户画像查询', '/api/serving/users/{id}/profile', 'GET', '用户服务', '王芳', 'published', 'v1', '返回用户基础属性与标签集合', 'admin'),
    ('API_STOCK_SYNC', '库存批量同步', '/api/serving/stock/sync', 'POST', '商品服务', '李明', 'registered', 'v1', '批量写入商品库存变动，供库存预警消费', 'admin'),
    ('API_REPORT_EXPORT', '月度报表导出', '/api/serving/reports/monthly/export', 'POST', '报表服务', '张伟', 'deprecated', 'v3', '旧版报表导出，已由驾驶舱在线查看替代', 'admin')
ON CONFLICT DO NOTHING;

-- 数据产品：面向业务消费方的数据资产登记与生命周期管理。
CREATE TABLE data_product (
    id               BIGSERIAL PRIMARY KEY,
    product_code     VARCHAR(64)  NOT NULL,
    product_name     VARCHAR(128) NOT NULL,
    category         VARCHAR(64),
    lifecycle        VARCHAR(16)  NOT NULL DEFAULT 'planning' CHECK (lifecycle IN ('planning','developing','released','offline')),
    owner            VARCHAR(64),
    consumers        VARCHAR(256),
    sla              VARCHAR(128),
    description      VARCHAR(512),
    created_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_data_product_code UNIQUE (product_code)
);

CREATE INDEX idx_data_product_category ON data_product (category);

INSERT INTO data_product (product_code, product_name, category, lifecycle, owner, consumers, sla, description, created_by)
VALUES
    ('DP_SALES_DASHBOARD', '经营分析驾驶舱', '报表分析', 'released', '王芳', '管理层、运营部', '工作日 8:00 前完成昨日数据更新', '面向管理层的核心经营指标可视化产品', 'admin'),
    ('DP_USER_TAG', '用户标签服务', '数据服务', 'developing', '张伟', '营销系统、推荐系统', '标签 T+1 更新，接口 P99 < 300ms', '统一用户标签体系，支撑精准营销与个性化推荐', 'admin'),
    ('DP_RISK_FEATURE', '风控特征集市', '特征工程', 'developing', '李明', '风控引擎', '实时特征延迟 < 5 分钟', '为实时风控决策提供特征变量订阅', 'admin'),
    ('DP_LEGACY_REPORT', '旧版月度报表', '报表分析', 'offline', '王芳', '（已下线）', '无', '历史遗留报表，已被经营分析驾驶舱替代', 'admin')
ON CONFLICT DO NOTHING;
