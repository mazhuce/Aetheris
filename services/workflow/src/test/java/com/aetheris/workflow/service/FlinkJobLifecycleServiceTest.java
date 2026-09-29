package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlinkJobLifecycleServiceTest {

    @Test
    void bindsJobAndPersistsLatestCheckpointEvidence() {
        Fixture fixture = new Fixture();
        when(fixture.jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(fixture.jobManager.status("job-1"))
                .thenReturn(new FlinkJobManagerClient.JobStatus("job-1", "RUNNING", "orders"));
        when(fixture.jobManager.checkpoints("job-1"))
                .thenReturn(new FlinkJobManagerClient.CheckpointSummary(
                        "job-1", 42L, "COMPLETED", 100L, 120L));

        Map<String, Object> result = fixture.service.bindFlinkJob(7L, 9L, "job-1");

        assertEquals("job-1", result.get("jobId"));
        assertEquals("RUNNING", result.get("state"));
        assertEquals(42L, result.get("latestCompletedCheckpointId"));
        verify(fixture.jdbc, atLeastOnce()).update(anyString(), any(Object[].class));
    }

    @Test
    void triggersSavepointAndKeepsAsynchronousRequestHandle() {
        Fixture fixture = new Fixture();
        when(fixture.jobManager.triggerSavepoint("job-1", "s3://warehouse/savepoints", false))
                .thenReturn(new FlinkJobManagerClient.TriggeredOperation("job-1", "request-1"));

        Map<String, Object> result = fixture.service.triggerFlinkSavepoint(
                7L, 9L, "s3://warehouse/savepoints", false);

        assertEquals("request-1", result.get("requestId"));
        assertEquals(false, result.get("cancelJob"));
        verify(fixture.jdbc, atLeastOnce()).update(anyString(), any(Object[].class));
    }

    @Test
    void pausesOnlyThroughStopWithSavepointAndPersistsPauseIntent() {
        Fixture fixture = new Fixture();
        when(fixture.jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(fixture.jobManager.stopWithSavepoint("job-1", "s3://warehouse/savepoints", false))
                .thenReturn(new FlinkJobManagerClient.TriggeredOperation("job-1", "pause-request"));

        Map<String, Object> result = fixture.service.pauseFlink(7L, 9L, "s3://warehouse/savepoints");

        assertEquals("pause-request", result.get("requestId"));
        assertEquals("pause", result.get("intent"));
        verify(fixture.jobManager).stopWithSavepoint("job-1", "s3://warehouse/savepoints", false);
    }

    @Test
    void resumesStreamingAttemptFromPersistedSavepoint() {
        Fixture fixture = new Fixture();
        Task task = new Task();
        task.setId(1L);
        task.setTaskType("flink");
        task.setFlinkMode("streaming");
        task.setFlinkEndpoint("http://flink:8083");
        task.setTaskSql("INSERT INTO sink SELECT 1");
        when(fixture.tasks.getById(1L)).thenReturn(task);
        when(fixture.jdbc.queryForMap(anyString(), eq(9L), eq(7L))).thenReturn(Map.of(
                "flink_job_id", "job-old",
                "flink_lifecycle_state", "paused",
                "flink_savepoint_request_id", "request-1",
                "flink_lifecycle_intent", "",
                "flink_savepoint_path", "s3://warehouse/savepoints/sp-1",
                "remote_handle", "old-session/old-operation"));
        when(fixture.jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(fixture.sql.startStreamingFromSavepoint(
                "http://flink:8083", "INSERT INTO sink SELECT 1",
                "s3://warehouse/savepoints/sp-1", "attempt-9"))
                .thenReturn(new FlinkSqlExecutionClient.ExecutionResult(
                        null, 0L, "new-session", "new-operation"));

        Map<String, Object> result = fixture.service.resumeFlink(7L, 9L);

        assertEquals("new-session", result.get("sessionHandle"));
        assertEquals("running", result.get("state"));
        verify(fixture.sql).startStreamingFromSavepoint(
                "http://flink:8083", "INSERT INTO sink SELECT 1",
                "s3://warehouse/savepoints/sp-1", "attempt-9");
    }

    @Test
    void upgradesByPersistingReplacementSqlBeforeStopWithSavepoint() {
        Fixture fixture = new Fixture();
        String replacement = "INSERT INTO sink SELECT id FROM source_orders";
        when(fixture.jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(fixture.jobManager.stopWithSavepoint("job-1", "s3://warehouse/savepoints", false))
                .thenReturn(new FlinkJobManagerClient.TriggeredOperation("job-1", "upgrade-request"));

        Map<String, Object> result = fixture.service.upgradeFlink(
                7L, 9L, "s3://warehouse/savepoints", replacement);

        assertEquals("upgrade-request", result.get("requestId"));
        assertEquals("upgrade", result.get("intent"));
        assertEquals(64, ((String) result.get("definitionDigest")).length());
        verify(fixture.jobManager).stopWithSavepoint("job-1", "s3://warehouse/savepoints", false);
    }

    @Test
    void cancelsBoundStreamingJobThroughJobManagerNotFinishedGatewayOperation() {
        Fixture fixture = new Fixture();
        when(fixture.jdbc.queryForMap(anyString(), eq(9L), eq(7L))).thenReturn(Map.of(
                "remote_handle", "session-1/operation-1", "flink_job_id", "job-1"));
        when(fixture.tasks.getById(1L)).thenReturn(new Task());
        TaskInstance result = new TaskInstance();
        result.setId(7L);
        when(fixture.claims.requestCancelFlink(7L, 9L)).thenReturn(result);

        assertEquals(7L, fixture.service.cancelFlink(7L, 9L).getId());

        verify(fixture.jobManager).cancel("job-1");
        org.mockito.Mockito.verify(fixture.sql, org.mockito.Mockito.never())
                .cancelHandle(anyString(), anyString(), anyString());
    }

    private static final class Fixture {
        final TaskService tasks = mock(TaskService.class);
        final TaskInstanceService instances = mock(TaskInstanceService.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final FlinkJobManagerClient jobManager = mock(FlinkJobManagerClient.class);
        final FlinkSqlExecutionClient sql = mock(FlinkSqlExecutionClient.class);
        final ExecutionClaimCoordinator claims = mock(ExecutionClaimCoordinator.class);
        final TaskExecutionService service;

        Fixture() {
            TaskInstance instance = new TaskInstance();
            instance.setId(7L);
            instance.setTaskId(1L);
            instance.setStatus("running");
            instance.setBizDate("2026-09-19");
            when(instances.getById(7L)).thenReturn(instance);
            when(jdbc.queryForMap(anyString(), eq(9L), eq(7L))).thenReturn(Map.of(
                    "flink_job_id", "job-1",
                    "flink_lifecycle_state", "running",
                    "flink_savepoint_request_id", ""));
            service = new TaskExecutionService(
                    tasks, instances, mock(TaskLogService.class), mock(TaskDependencyService.class),
                    claims, mock(SqlExecutionClient.class),
                    mock(NotificationClient.class), mock(SparkBatchExecutionService.class),
                    sql, jobManager, mock(QualityExecutionClient.class),
                    mock(LineageEvidenceClient.class), jdbc);
        }
    }
}
