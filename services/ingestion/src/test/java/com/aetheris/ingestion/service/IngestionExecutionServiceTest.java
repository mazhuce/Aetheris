package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.entity.IngestionTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class IngestionExecutionServiceTest {

    private final IngestionTaskService taskService = mock(IngestionTaskService.class);
    private final IngestionRunService runService = mock(IngestionRunService.class);
    private final MetadataIngestionClient client = mock(MetadataIngestionClient.class);
    private final NotificationClient notificationClient = mock(NotificationClient.class);
    private final IngestionExecutionService service = new IngestionExecutionService(
            taskService, runService, client, notificationClient);

    @Test
    void recordsRealTransferCounts() {
        IngestionTask task = configuredTask();
        when(taskService.getById(1L)).thenReturn(task);
        when(client.execute(task)).thenReturn(new MetadataIngestionClient.TransferResult(12, 12, 45));

        IngestionRun result = service.execute(1L, "admin");

        assertEquals("success", result.getStatus());
        assertEquals(12L, result.getRowsWritten());
        assertEquals(12L, task.getLastRunRows());
        assertNotNull(result.getFinishedAt());
        verify(taskService).updateById(task);
    }

    @Test
    void persistsFailedRunAndNeverReportsSuccess() {
        IngestionTask task = configuredTask();
        when(taskService.getById(1L)).thenReturn(task);
        when(client.execute(task)).thenThrow(new MetadataIngestionClient.IngestionExecutionException("目标表不存在"));

        assertThrows(MetadataIngestionClient.IngestionExecutionException.class,
                () -> service.execute(1L, "admin"));

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runService).updateById(captor.capture());
        assertEquals("failed", captor.getValue().getStatus());
        assertEquals("failed", task.getLastRunStatus());
        assertEquals("目标表不存在", task.getLastError());
    }

    @Test
    void rejectsIncompleteLegacyTask() {
        IngestionTask task = new IngestionTask();
        task.setId(1L);
        task.setStatus("enabled");
        when(taskService.getById(1L)).thenReturn(task);

        assertThrows(IllegalArgumentException.class, () -> service.execute(1L, "admin"));
    }

    @Test
    void reusesCompletedRunForSameIdempotencyKey() {
        IngestionTask task = configuredTask();
        when(taskService.getById(1L)).thenReturn(task);
        when(client.execute(task)).thenReturn(new MetadataIngestionClient.TransferResult(2, 2, 10));
        IngestionRun completed = service.execute(1L, "admin", "request-42");
        completed.setId(42L);
        when(runService.getOne(any(), eq(false))).thenReturn(completed);

        IngestionRun retry = service.execute(1L, "admin", "request-42");

        assertEquals(completed, retry);
        verify(client).execute(task);
    }

    @Test
    void classifiesUpstreamFailureForDiagnosis() {
        IngestionTask task = configuredTask();
        when(taskService.getById(1L)).thenReturn(task);
        when(client.execute(task)).thenThrow(new MetadataIngestionClient.IngestionExecutionException("连接失败"));

        assertThrows(MetadataIngestionClient.IngestionExecutionException.class,
                () -> service.execute(1L, "admin", "request-failed"));

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runService).updateById(captor.capture());
        assertEquals("UPSTREAM_TRANSFER_FAILED", captor.getValue().getFailureCode());
    }

    private IngestionTask configuredTask() {
        IngestionTask task = new IngestionTask();
        task.setId(1L);
        task.setStatus("enabled");
        task.setSourceDatasourceId(10L);
        task.setTargetDatasourceId(20L);
        task.setSourceSql("SELECT id FROM orders");
        task.setTargetTable("ods.orders");
        task.setWriteMode("append");
        task.setBatchSize(500);
        return task;
    }
}
