package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseSchemaChange;
import com.aetheris.metadata.entity.LakehouseTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaEvolutionSqlPlannerTest {
    @Test
    void plansSafeAddColumnWithQuotedIcebergIdentifier() {
        LakehouseSchemaChange change = change("add_column", "proposed", "auto_apply");
        change.setColumnName("customer_id");
        change.setToType("bigint");

        var plan = SchemaEvolutionSqlPlanner.plan(table(), change);

        assertEquals("ALTER TABLE `lakehouse`.`bronze`.`orders` ADD COLUMN `customer_id` BIGINT", plan.statement());
        assertTrue(plan.readyForExecution());
        assertTrue(!plan.destructive());
    }

    @Test
    void requiresApprovalForRenameAndEscapesComments() {
        LakehouseSchemaChange rename = change("rename_column", "proposed", "approval_required");
        rename.setColumnName("old_name");
        rename.setNewColumnName("new_name");
        assertThrows(IllegalArgumentException.class, () -> SchemaEvolutionSqlPlanner.plan(table(), rename));

        LakehouseSchemaChange comment = change("comment", "proposed", "auto_apply");
        comment.setColumnName("status");
        comment.setReason("客户's状态");
        assertEquals("ALTER TABLE `lakehouse`.`bronze`.`orders` ALTER COLUMN `status` COMMENT '客户''s状态'",
                SchemaEvolutionSqlPlanner.plan(table(), comment).statement());
    }

    @Test
    void rejectsUnsafeTypesIdentifiersAndIsolatedChanges() {
        LakehouseSchemaChange change = change("widen_type", "proposed", "auto_apply");
        change.setColumnName("status;DROP");
        change.setToType("string");
        assertThrows(IllegalArgumentException.class, () -> SchemaEvolutionSqlPlanner.plan(table(), change));

        change.setColumnName("status");
        change.setToType("map<string,string>");
        assertThrows(IllegalArgumentException.class, () -> SchemaEvolutionSqlPlanner.plan(table(), change));

        LakehouseSchemaChange isolated = change("narrow_type", "isolated", "isolate");
        isolated.setColumnName("amount");
        isolated.setToType("int");
        assertThrows(IllegalArgumentException.class, () -> SchemaEvolutionSqlPlanner.plan(table(), isolated));
    }

    private static LakehouseTable table() {
        LakehouseTable table = new LakehouseTable();
        table.setCatalogName("lakehouse");
        table.setNamespaceName("bronze");
        table.setTableName("orders");
        return table;
    }

    private static LakehouseSchemaChange change(String operation, String status, String decision) {
        LakehouseSchemaChange change = new LakehouseSchemaChange();
        change.setOperation(operation);
        change.setStatus(status);
        change.setDecision(decision);
        return change;
    }
}
