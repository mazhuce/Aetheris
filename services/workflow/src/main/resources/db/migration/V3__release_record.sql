-- 发布管理表：数仓产出（表/任务/看板/API/模型）的发布申请与审批流转。
--
-- 设计要点：
--   1. 各类发布物共用一张表：审批与发布对所有类型是同一套流程，
--      按类型拆表只会让「待我审批」这类列表跨表 UNION，得不偿失。
--   2. 状态机 pending → approved → released，另有 pending → rejected 终态分支与
--      released → rolled_back 回退；流转规则由应用层控制（非法流转返回 409），
--      库层只约束枚举取值合法，两层职责分开。
--   3. release_no 带日期前缀且唯一（如 REL-20260902-001），作为跨系统引用与
--      审计追溯的锚点，自增主键不对外。
--   4. requested_by / approved_by 刻意不做外键：用户属于 system 服务的边界，
--      有效性由应用层（网关透传的 X-User-Name）保证，避免库层耦合两个服务。

CREATE TABLE IF NOT EXISTS release_record (
    id             BIGSERIAL PRIMARY KEY,
    -- 发布编号，形如 REL-20260902-001。
    release_no     VARCHAR(32)  NOT NULL,
    title          VARCHAR(128) NOT NULL,
    -- 发布物类型：table=数据表，task=调度任务，dashboard=看板，api=接口，model=模型。
    release_type   VARCHAR(16)  NOT NULL DEFAULT 'table',
    -- 发布对象名称，如目标表名、看板名；同一对象可多次发布，靠版本号区分。
    target_name    VARCHAR(256) NOT NULL,
    -- 发布版本；为空表示尚未定版。
    target_version VARCHAR(32),
    -- 变更摘要：给审批人看，说明这次改了什么、影响哪些下游。
    change_summary TEXT,
    -- pending=待审批，approved=已通过，rejected=已拒绝，released=已发布，rolled_back=已回滚。
    status         VARCHAR(24)  NOT NULL DEFAULT 'pending',
    -- 申请人 / 审批人：来自网关透传的 X-User-Name。
    requested_by   VARCHAR(64),
    approved_by    VARCHAR(64),
    -- 实际发布时间：approved 只代表审批通过，released 才落此字段；
    -- 回滚后保留原值，作为发布窗口的留痕。
    released_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_release_record_no UNIQUE (release_no),
    CONSTRAINT chk_release_type
        CHECK (release_type IN ('table', 'task', 'dashboard', 'api', 'model')),
    CONSTRAINT chk_release_status
        CHECK (status IN ('pending', 'approved', 'rejected', 'released', 'rolled_back'))
);

-- 状态过滤是列表页高频查询（如「待我审批」）；创建时间倒序支撑默认列表。
CREATE INDEX IF NOT EXISTS idx_release_record_status ON release_record (status);
CREATE INDEX IF NOT EXISTS idx_release_record_created ON release_record (created_at DESC);

-- 种子数据：覆盖已发布 / 待审批 / 已回滚几种典型状态，联调时列表页有真实数据可看。
-- ON CONFLICT DO NOTHING：编号重复时静默跳过，环境重建脚本可重复执行。
INSERT INTO release_record
    (release_no, title, release_type, target_name, target_version,
     change_summary, status, requested_by, approved_by, released_at)
VALUES
    ('REL-20260902-001', '订单明细宽表上线', 'table', 'dws.dwd_order_detail', 'v1.0.0',
     '新增订单明细宽表，聚合订单与支付信息，供下游销售报表使用。',
     'released', 'zhangsan', 'lisi', now() - interval '2 day'),
    ('REL-20260902-002', '每日销售汇总口径调整', 'task', 'daily_sales_summary_job', 'v1.1.0',
     '统计口径剔除退款订单，需重跑近 7 天分区。',
     'pending', 'wangwu', NULL, NULL),
    ('REL-20260902-003', '经营看板数据源切换', 'dashboard', '经营分析看板', 'v2.3.1',
     '看板数据源由旧明细表切换到新宽表，字段映射同步更新。',
     'pending', 'zhangsan', NULL, NULL),
    ('REL-20260902-004', '用户标签模型精度回滚', 'model', 'user_tag_model', 'v3.2.0',
     '标签模型精度未达预期，回滚到上一稳定版本 v3.1.4。',
     'rolled_back', 'zhaoliu', 'lisi', now() - interval '3 day')
ON CONFLICT (release_no) DO NOTHING;
