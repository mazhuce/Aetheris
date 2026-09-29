package com.aetheris.workflow.service;

import com.aetheris.workflow.service.ExecutionAdapter.ManagedHandle;
import com.aetheris.workflow.service.ExecutionAdapter.Submission;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LakehouseMaintenanceExecutionServiceTest {
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void submitsOnlyAfterRunnerArtifactDigestValidation() {
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        when(adapter.submit(any(Submission.class))).thenReturn(
                new ManagedHandle("0123456789abcdef0123456789abcdef", "maintenance-1", "running", null, DIGEST));
        LakehouseMaintenanceExecutionService service = new LakehouseMaintenanceExecutionService(
                adapter, true, "aetheris_lakehouse_maintenance.py", DIGEST);

        var result = service.submit(new LakehouseMaintenanceExecutionService.SubmitRequest(
                "maintenance-1", "2026-09-19", "12", "rewrite_data_files",
                "CALL rest.system.rewrite_data_files(table => 'bronze.orders')", 600));

        assertEquals("maintenance-1", result.requestKey());
        verify(adapter).validate("aetheris_lakehouse_maintenance.py", DIGEST);
        verify(adapter).submit(argThat(value -> value.parameters().containsKey("maintenance")
                && value.parameters().get("maintenance").toString().contains("rewrite_data_files")));
    }

    @Test
    void rejectsSemicolonAndOperationMismatchBeforeRunnerCall() {
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        LakehouseMaintenanceExecutionService service = new LakehouseMaintenanceExecutionService(
                adapter, true, "aetheris_lakehouse_maintenance.py", DIGEST);

        assertThrows(IllegalArgumentException.class, () -> service.submit(
                new LakehouseMaintenanceExecutionService.SubmitRequest(
                        "maintenance-1", "2026-09-19", "12", "rollback",
                        "CALL rest.system.rollback_to_snapshot('1');", 600)));
        assertThrows(IllegalArgumentException.class, () -> service.submit(
                new LakehouseMaintenanceExecutionService.SubmitRequest(
                        "maintenance-2", "2026-09-19", "12", "storage_estimate",
                        "CALL rest.system.rewrite_data_files(table => 'bronze.orders')", 600)));
        verifyNoInteractions(adapter);
    }

    @Test
    void missingRemoteHandleRemainsUnknown() {
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        when(adapter.reconcile("maintenance-1")).thenReturn(Optional.empty());
        LakehouseMaintenanceExecutionService service = new LakehouseMaintenanceExecutionService(
                adapter, true, "aetheris_lakehouse_maintenance.py", DIGEST);
        assertEquals(Optional.empty(), service.status("maintenance-1"));
    }

    @Test
    void submitsSchemaChangeThroughTheSameApprovedRunnerContract() {
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        when(adapter.submit(any(Submission.class))).thenReturn(
                new ManagedHandle("0123456789abcdef0123456789abcdef", "schema-change-1", "running", null, DIGEST));
        LakehouseMaintenanceExecutionService service = new LakehouseMaintenanceExecutionService(
                adapter, true, "aetheris_lakehouse_maintenance.py", DIGEST);

        service.submit(new LakehouseMaintenanceExecutionService.SubmitRequest(
                "schema-change-1", "2026-09-19", "13", "add_column",
                "ALTER TABLE `lakehouse`.`bronze`.`orders` ADD COLUMN `customer_id` BIGINT", 600));

        verify(adapter).submit(argThat(value -> value.parameters().containsKey("schemaChange")
                && value.parameters().get("schemaChange").toString().contains("add_column")
                && !value.parameters().containsKey("maintenance")));
    }
}
