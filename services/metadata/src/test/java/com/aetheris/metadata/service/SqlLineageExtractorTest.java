package com.aetheris.metadata.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlLineageExtractorTest {

    @Test
    @DisplayName("INSERT OVERWRITE ... PARTITION：JSqlParser 解析不了的写法，这里必须支持")
    void extractsFromInsertOverwritePartition() {
        // 这正是 JSqlParser 4.9 抛 JSQLParserException 的语法，是自研提取器的存在理由。
        String sql = """
                INSERT OVERWRITE TABLE dwd.dwd_order PARTITION (dt)
                SELECT o.order_id FROM ods.ods_order o WHERE o.is_deleted = '0'
                DISTRIBUTE BY dt
                """;

        List<SqlLineageExtractor.TableLineage> result = SqlLineageExtractor.extract(sql);

        assertEquals(1, result.size());
        assertEquals("dwd.dwd_order", result.get(0).targetTable());
        assertEquals(List.of("ods.ods_order"), result.get(0).sourceTables());
    }

    @Test
    @DisplayName("多个来源表：JOIN 的每一张表都算来源")
    void extractsMultipleSources() {
        String sql = """
                INSERT OVERWRITE TABLE ads.ads_order_stats
                SELECT d.stat_date FROM dws.dws_order_daily d
                LEFT JOIN dim.dim_date dd ON d.stat_date = dd.date_key
                """;

        List<SqlLineageExtractor.TableLineage> result = SqlLineageExtractor.extract(sql);

        assertEquals(1, result.size());
        assertEquals("ads.ads_order_stats", result.get(0).targetTable());
        assertEquals(List.of("dws.dws_order_daily", "dim.dim_date"), result.get(0).sourceTables());
    }

    @Test
    @DisplayName("LATERAL VIEW EXPLODE 与表值函数 range() 不算来源表")
    void ignoresTableValuedFunctionsAndLateralView() {
        String sql = """
                INSERT OVERWRITE TABLE ods.ods_order_item PARTITION (dt)
                SELECT x FROM ods.ods_order o
                LATERAL VIEW EXPLODE(SEQUENCE(1, 3)) i AS idx
                DISTRIBUTE BY o.dt
                """;

        List<SqlLineageExtractor.TableLineage> result = SqlLineageExtractor.extract(sql);

        assertEquals(1, result.size());
        // EXPLODE/SEQUENCE/LATERAL 都不是物理表，混进血缘会产生不存在的节点。
        assertEquals(List.of("ods.ods_order"), result.get(0).sourceTables());
    }

    @Test
    @DisplayName("range() 表值函数不算来源表")
    void ignoresRangeFunction() {
        String sql = """
                INSERT OVERWRITE TABLE ods.ods_user
                SELECT id FROM range(0, 100000)
                """;

        // 只有 range() 一个「来源」，剔除后无来源，因此不产生血缘边。
        assertTrue(SqlLineageExtractor.extract(sql).isEmpty());
    }

    @Test
    @DisplayName("CTE 名称不算来源表")
    void ignoresCteNames() {
        String sql = """
                INSERT OVERWRITE TABLE ads.ads_user_profile
                WITH monthly AS (SELECT user_id FROM dwd.dwd_order),
                     ranked AS (SELECT user_id FROM monthly)
                SELECT user_id FROM ranked
                """;

        List<SqlLineageExtractor.TableLineage> result = SqlLineageExtractor.extract(sql);

        assertEquals(1, result.size());
        // monthly / ranked 是查询内部的临时结果，不是物理表。
        assertEquals(List.of("dwd.dwd_order"), result.get(0).sourceTables());
    }

    @Test
    @DisplayName("自引用不构成血缘边")
    void ignoresSelfReference() {
        String sql = "INSERT INTO TABLE ods.ods_order SELECT * FROM ods.ods_order WHERE dt = '2026-08-01'";

        assertTrue(SqlLineageExtractor.extract(sql).isEmpty());
    }

    @Test
    @DisplayName("注释与字符串字面量里的 from/join 不被误读")
    void ignoresCommentsAndStringLiterals() {
        String sql = """
                -- 这里说明数据 from ods.fake_table 加工而来
                /* 也可能 join dim.fake_dim */
                INSERT OVERWRITE TABLE dwd.dwd_product
                SELECT p.id, 'copied from ods.another_fake' AS note
                FROM ods.ods_product p
                """;

        List<SqlLineageExtractor.TableLineage> result = SqlLineageExtractor.extract(sql);

        assertEquals(1, result.size());
        assertEquals(List.of("ods.ods_product"), result.get(0).sourceTables());
    }

    @Test
    @DisplayName("一段脚本含多条语句时逐条提取")
    void extractsMultipleStatements() {
        String sql = """
                INSERT OVERWRITE TABLE dwd.dwd_user SELECT * FROM ods.ods_user;
                INSERT OVERWRITE TABLE dws.dws_user_daily PARTITION (dt)
                SELECT * FROM dwd.dwd_user GROUP BY dt;
                """;

        List<SqlLineageExtractor.TableLineage> result = SqlLineageExtractor.extract(sql);

        assertEquals(2, result.size());
        assertEquals("dwd.dwd_user", result.get(0).targetTable());
        assertEquals("dws.dws_user_daily", result.get(1).targetTable());
        assertEquals(List.of("dwd.dwd_user"), result.get(1).sourceTables());
    }

    @Test
    @DisplayName("CREATE TABLE AS SELECT 也能识别")
    void extractsFromCreateTableAsSelect() {
        String sql = "CREATE TABLE IF NOT EXISTS dws.dws_tmp AS SELECT * FROM dwd.dwd_order";

        List<SqlLineageExtractor.TableLineage> result = SqlLineageExtractor.extract(sql);

        assertEquals(1, result.size());
        assertEquals("dws.dws_tmp", result.get(0).targetTable());
        assertEquals(List.of("dwd.dwd_order"), result.get(0).sourceTables());
    }

    @Test
    @DisplayName("反引号与大小写归一")
    void normalizesBackticksAndCase() {
        String sql = "INSERT OVERWRITE TABLE `DWD`.`DWD_ORDER` SELECT * FROM `ODS`.`ODS_ORDER`";

        List<SqlLineageExtractor.TableLineage> result = SqlLineageExtractor.extract(sql);

        assertEquals("dwd.dwd_order", result.get(0).targetTable());
        assertEquals(List.of("ods.ods_order"), result.get(0).sourceTables());
    }

    @Test
    @DisplayName("纯 SELECT 或空输入不产生血缘")
    void returnsEmptyForNonWriteStatements() {
        assertTrue(SqlLineageExtractor.extract("SELECT * FROM ods.ods_order").isEmpty());
        assertTrue(SqlLineageExtractor.extract("").isEmpty());
        assertTrue(SqlLineageExtractor.extract(null).isEmpty());
        assertTrue(SqlLineageExtractor.extract("   ").isEmpty());
    }

    @Test
    @DisplayName("重复来源表去重")
    void deduplicatesSources() {
        String sql = """
                INSERT OVERWRITE TABLE ads.ads_order_stats
                SELECT a.x FROM dws.dws_order_daily a
                JOIN dws.dws_order_daily b ON a.dt = b.dt
                """;

        assertEquals(List.of("dws.dws_order_daily"),
                SqlLineageExtractor.extract(sql).get(0).sourceTables());
    }
}
