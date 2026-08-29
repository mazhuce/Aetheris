-- 血缘来源标记：区分人工登记与 SQL 解析，并清理最初的示例种子数据。
--
-- source 取值：
--   manual = 人工在界面登记，解析不覆盖；
--   parsed = 从任务 SQL 解析得出，每次重新解析同一目标表时整体替换该表的入边。
--
-- V3 插入的两条示例血缘（ods_orders → dwd_orders、dwd_orders → ads_order_stats）
-- 是骨架期的占位数据，对应的表在真实数仓中并不存在（真实表名是 ods_order 单数形式），
-- 留着会在血缘图上产生永远连不上的孤立节点，因此在此删除。

ALTER TABLE lineage_relation
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'manual';

ALTER TABLE lineage_relation
    ADD CONSTRAINT chk_lineage_relation_source
        CHECK (source IN ('manual', 'parsed'));

-- 同一条边只保留一份，便于解析时按 (source_table, target_table) 幂等替换。
DELETE FROM lineage_relation a
WHERE EXISTS (
    SELECT 1 FROM lineage_relation b
    WHERE b.source_table = a.source_table
      AND b.target_table = a.target_table
      AND b.id < a.id
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lineage_relation_edge
    ON lineage_relation (source_table, target_table);

CREATE INDEX IF NOT EXISTS idx_lineage_relation_target
    ON lineage_relation (target_table);

-- 删除骨架期的示例血缘（表名为复数，真实数仓中不存在）。
DELETE FROM lineage_relation
WHERE (source_table, target_table) IN (
    ('ods_orders', 'dwd_orders'),
    ('dwd_orders', 'ads_order_stats')
);
