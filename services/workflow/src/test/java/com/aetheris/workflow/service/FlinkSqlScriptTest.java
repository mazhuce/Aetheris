package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlinkSqlScriptTest {
    @Test
    void keepsQuotedSemicolonInsideCdcSetupAndRequiresFinalInsert() {
        var statements = FlinkSqlScript.statements("""
                -- isolated stream setup
                CREATE TEMPORARY TABLE source_orders (id BIGINT) WITH ('password'='x;y');
                /* catalog switch */ USE CATALOG lakehouse;
                INSERT INTO bronze.orders SELECT id FROM source_orders;
                """, true);
        assertEquals(3, statements.size());
        assertEquals(true, statements.getFirst().contains("x;y"));
    }

    @Test
    void rejectsDestructiveSetupAndSavepointOverride() {
        assertThrows(IllegalArgumentException.class, () -> FlinkSqlScript.statements(
                "DROP TABLE bronze.orders; INSERT INTO bronze.orders SELECT 1", true));
        assertThrows(IllegalArgumentException.class, () -> FlinkSqlScript.statements(
                "SET 'execution.savepoint.path'='s3://other'; INSERT INTO sink SELECT 1", true));
        assertThrows(IllegalArgumentException.class, () -> FlinkSqlScript.statements(
                "CREATE TABLE source_orders (id BIGINT)", true));
    }
}
