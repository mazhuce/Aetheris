package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseMaintenanceJob;
import com.aetheris.metadata.entity.LakehouseTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LakehouseMaintenancePlanTest {
    @Test
    void buildsSafeRewritePlanWithoutExecutingAnything() {
        LakehouseTable table = table();
        LakehouseMaintenanceJob job = job("rewrite_data_files");

        LakehouseMaintenancePlan.Plan plan = LakehouseMaintenancePlan.build(table, job);

        assertEquals("lakehouse.bronze.orders", plan.tableIdentifier());
        assertEquals("CALL lakehouse.system.rewrite_data_files(table => 'bronze.orders')", plan.statement());
        assertEquals(true, plan.mutating());
    }

    @Test
    void requiresNumericSnapshotForRollback() {
        LakehouseMaintenanceJob job = job("rollback");
        job.setRequestedSnapshotId("1 OR 1=1");

        assertThrows(IllegalArgumentException.class, () -> LakehouseMaintenancePlan.build(table(), job));
    }

    @Test
    void buildsStorageEstimateAsReadOnlyPlan() {
        LakehouseMaintenanceJob job = job("storage_estimate");

        LakehouseMaintenancePlan.Plan plan = LakehouseMaintenancePlan.build(table(), job);

        assertEquals(false, plan.mutating());
        assertEquals(true, plan.statement().contains("file_size_in_bytes"));
    }

    @Test
    void rejectsUnsafeTableIdentifierAndRetention() {
        LakehouseTable unsafeIdentifier = table();
        unsafeIdentifier.setTableName("orders;DROP TABLE users");
        assertThrows(IllegalArgumentException.class,
                () -> LakehouseMaintenancePlan.build(unsafeIdentifier, job("storage_estimate")));

        LakehouseTable invalidRetention = table();
        invalidRetention.setRetentionDays(0);
        assertThrows(IllegalArgumentException.class,
                () -> LakehouseMaintenancePlan.build(invalidRetention, job("expire_snapshots")));
    }

    private LakehouseTable table() {
        LakehouseTable table = new LakehouseTable();
        table.setCatalogName("lakehouse");
        table.setNamespaceName("bronze");
        table.setTableName("orders");
        table.setRetentionDays(30);
        return table;
    }

    private LakehouseMaintenanceJob job(String operation) {
        LakehouseMaintenanceJob job = new LakehouseMaintenanceJob();
        job.setOperation(operation);
        return job;
    }
}
