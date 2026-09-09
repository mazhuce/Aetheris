package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.ExecutionClaimCoordinator.ExecutionClaim;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobView;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SparkBatchExecutionServiceTest {
    @Test
    void unknownSubmitIsRecordedAndNeverRetriedInline() {
        ManagedSparkCatalogService catalog = mock(ManagedSparkCatalogService.class);
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        ExecutionClaimCoordinator coordinator = mock(ExecutionClaimCoordinator.class);
        TaskLogService logs = mock(TaskLogService.class);
        SparkBatchExecutionService service = new SparkBatchExecutionService(catalog, adapter, coordinator,
                logs, mock(JdbcTemplate.class), mock(LineageEvidenceClient.class));
        Task task = task();
        ExecutionClaim claim = claim();
        when(catalog.runnableJob(9L)).thenReturn(job());
        when(adapter.submit(any())).thenThrow(new SparkRunnerException("unknown", 503, true));
        TaskInstance unknown = claim.instance(); unknown.setStatus("unknown");
        when(coordinator.submissionUnknown(claim, "unknown")).thenReturn(unknown);

        assertEquals("unknown", service.submit(task, claim).getStatus());
        verify(adapter, times(1)).submit(any());
        verify(adapter, never()).reconcile(anyString());
        verify(coordinator).submissionUnknown(claim, "unknown");
    }

    private static Task task() {
        Task task = new Task(); task.setId(3L); task.setName("managed");
        task.setTaskType("spark"); task.setJobDefinitionId(9L); return task;
    }

    private static ExecutionClaim claim() {
        TaskInstance instance = new TaskInstance(); instance.setId(4L); instance.setTaskId(3L);
        instance.setBizDate("2026-09-08"); instance.setStatus("submitting");
        instance.setAttempt(1); instance.setFencingToken(7L); instance.setActiveAttemptId(5L);
        return new ExecutionClaim(instance, 5L, 1, 7L, null);
    }

    private static SparkJobView job() {
        return new SparkJobView(9L, "job", 1, 1L, "local", "active", 2L, "artifact",
                "order_daily_profile.py", "a".repeat(64), "approved",
                JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                List.of("hive_prod.dwd.dwd_order"), null,
                "hdfs://localhost:9000/user/aetheris/managed/order_daily_profile",
                "overwrite_partition", 1, 768, 1800, 0, "published", "b".repeat(64),
                "system", null, "system", null, null, null);
    }
}
