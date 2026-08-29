package com.aetheris.metadata.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlWriteGuardTest {

    @Test
    @DisplayName("放行数仓加工的主力写法")
    void allowsWarehouseInsertStatements() {
        assertEquals(
                "INSERT OVERWRITE TABLE dwd.dwd_order PARTITION (dt) SELECT * FROM ods.ods_order",
                SqlWriteGuard.validate(
                        "INSERT OVERWRITE TABLE dwd.dwd_order PARTITION (dt) SELECT * FROM ods.ods_order"));
        assertEquals(
                "INSERT INTO dws.dws_order_daily SELECT dt, COUNT(*) FROM dwd.dwd_order GROUP BY dt",
                SqlWriteGuard.validate(
                        "INSERT INTO dws.dws_order_daily SELECT dt, COUNT(*) FROM dwd.dwd_order GROUP BY dt"));
    }

    @Test
    @DisplayName("放行 CREATE ... AS SELECT 与 CREATE IF NOT EXISTS")
    void allowsCreateStatements() {
        assertTrue(SqlWriteGuard.isWriteStatement("CREATE TABLE dws.tmp AS SELECT * FROM dwd.dwd_order"));
        assertTrue(SqlWriteGuard.isWriteStatement("CREATE DATABASE IF NOT EXISTS ads"));
        assertTrue(SqlWriteGuard.isWriteStatement("CREATE OR REPLACE VIEW ads.v_daily AS SELECT 1"));
    }

    @Test
    @DisplayName("拒绝破坏性 DDL：DROP / TRUNCATE / ALTER")
    void rejectsDestructiveDdl() {
        // 这些不可逆，且 INSERT OVERWRITE 已能满足重跑覆盖需求，无需放行。
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("DROP TABLE dwd.dwd_order"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("TRUNCATE TABLE dwd.dwd_order"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("ALTER TABLE dwd.dwd_order ADD COLUMNS (x INT)"));
    }

    @Test
    @DisplayName("拒绝语句中间夹带分号的注入尝试")
    void rejectsStatementChaining() {
        // 白名单只看开头，若不拦分号，DROP 会跟在合法 INSERT 后面被一并执行。
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate(
                        "INSERT INTO dwd.t SELECT 1; DROP TABLE ods.ods_order"));
        assertTrue(error.getMessage().contains("单条语句"));
    }

    @Test
    @DisplayName("允许末尾单个分号，这是常见书写习惯")
    void allowsTrailingSemicolon() {
        assertEquals(
                "INSERT INTO dwd.t SELECT 1",
                SqlWriteGuard.validate("INSERT INTO dwd.t SELECT 1;"));
    }

    @Test
    @DisplayName("拒绝权限与非白名单语句")
    void rejectsNonWhitelistedStatements() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("GRANT ALL ON dwd.dwd_order TO PUBLIC"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("UPDATE dwd.dwd_order SET pay_amount = 0"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("DELETE FROM dwd.dwd_order"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("SELECT * FROM ods.ods_order"));
    }

    @Test
    @DisplayName("拒绝可读服务器文件或阻塞连接的函数")
    void rejectsDangerousFunctions() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("INSERT INTO dwd.t SELECT pg_read_file('/etc/passwd')"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("INSERT INTO dwd.t SELECT load_file('/etc/passwd')"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlWriteGuard.validate("INSERT INTO dwd.t SELECT 1 INTO OUTFILE '/tmp/x'"));
    }

    @Test
    @DisplayName("注释里的禁用词不误拦")
    void doesNotFlagKeywordsInsideComments() {
        // 真实加工脚本里常有「勿 DROP 此表」这类说明，误拦会让任务无法保存。
        String sql = """
                -- 该表由任务重跑覆盖，不要 DROP
                /* 历史上曾 TRUNCATE 过，现已改为 OVERWRITE */
                INSERT OVERWRITE TABLE dwd.dwd_order PARTITION (dt)
                SELECT * FROM ods.ods_order""";

        assertTrue(SqlWriteGuard.validate(sql).startsWith("--"));
    }

    @Test
    @DisplayName("字符串字面量里的禁用词不误拦")
    void doesNotFlagKeywordsInsideStringLiterals() {
        String sql = "INSERT INTO dwd.t SELECT 'drop this row' AS note FROM ods.ods_order";

        assertEquals(sql, SqlWriteGuard.validate(sql));
    }

    @Test
    @DisplayName("空语句被拒绝")
    void rejectsBlankSql() {
        assertThrows(IllegalArgumentException.class, () -> SqlWriteGuard.validate(null));
        assertThrows(IllegalArgumentException.class, () -> SqlWriteGuard.validate(""));
        assertThrows(IllegalArgumentException.class, () -> SqlWriteGuard.validate("   "));
    }

    @Test
    @DisplayName("isWriteStatement 能区分读写，用于选择执行路径")
    void distinguishesReadAndWrite() {
        assertTrue(SqlWriteGuard.isWriteStatement("INSERT INTO t SELECT 1"));
        assertTrue(SqlWriteGuard.isWriteStatement("  insert overwrite table t select 1"));
        assertFalse(SqlWriteGuard.isWriteStatement("SELECT * FROM t"));
        assertFalse(SqlWriteGuard.isWriteStatement(""));
        assertFalse(SqlWriteGuard.isWriteStatement(null));
    }

    @Test
    @DisplayName("大小写与换行不影响白名单匹配")
    void normalizesCaseAndWhitespace() {
        assertTrue(SqlWriteGuard.isWriteStatement("insert\n  overwrite\n  table t\nselect 1"));
        assertEquals("Insert Into t Select 1", SqlWriteGuard.validate("Insert Into t Select 1"));
    }
}
