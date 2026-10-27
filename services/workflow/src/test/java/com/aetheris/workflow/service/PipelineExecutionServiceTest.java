package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.fixedexecution.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PipelineExecutionServiceTest {
    @Test
    void actualNodeSnapshotIsAuthorizedBeforeCreatingAnyRunRows() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        TaskService tasks=mock(TaskService.class);
        when(tasks.getById(11L)).thenReturn(task(11L,"target","sql","enabled"));
        when(tasks.getById(12L)).thenReturn(task(12L,"upstream","sql","enabled"));
        when(jdbc.query(org.mockito.ArgumentMatchers.contains("SELECT upstream_task_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(),
                org.mockito.ArgumentMatchers.eq(11L))).thenReturn(List.of(12L));
        when(jdbc.query(org.mockito.ArgumentMatchers.contains("SELECT upstream_task_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Long>>any(),
                org.mockito.ArgumentMatchers.eq(12L))).thenReturn(List.of());
        var authorization=mock(WorkflowAuthorizationService.class);
        var service=new PipelineExecutionService(jdbc,tasks,mock(TaskInstanceService.class),
                mock(TaskExecutionService.class),new SyncTaskExecutor(),120,120);
        service.setAuthorization(authorization);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->service.start(11L,"2026-10-18","alice"));
        verify(authorization).canExecuteTasks("alice",List.of(12L,11L));
        verify(jdbc,never()).update(any(String.class),any(Object[].class));
        verify(jdbc,never()).queryForObject(org.mockito.ArgumentMatchers.contains("INSERT INTO"),eq(Long.class),any(Object[].class));
    }

    @Test
    void rejectsDisabledDependencyBeforeCreatingPipelineRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        Task target = task(11L, "指标汇总", "sql", "enabled");
        Task disabled = task(12L, "订单清洗", "spark", "disabled");
        when(tasks.getById(11L)).thenReturn(target);
        when(tasks.getById(12L)).thenReturn(disabled);
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), mock(TaskExecutionService.class),
                new SyncTaskExecutor(), 120, 120);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.validateRunnableTasks(List.of(12L, 11L)));

        assertTrue(error.getMessage().contains("订单清洗"));
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void rejectsUnsupportedTaskTypeBeforeCreatingPipelineRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        when(tasks.getById(11L)).thenReturn(task(11L, "遗留任务", "shell", "enabled"));
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), mock(TaskExecutionService.class),
                new SyncTaskExecutor(), 120, 120);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.validateRunnableTasks(List.of(11L)));

        assertTrue(error.getMessage().contains("类型不受支持"));
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void listScopesOrdinaryUsersAndBoundsLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, mock(TaskService.class), mock(TaskInstanceService.class),
                mock(TaskExecutionService.class), new SyncTaskExecutor(), 120, 120);

        service.list(500, "alice", false);

        verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("WHERE run.triggered_by=? OR task.owner=?"),
                org.mockito.ArgumentMatchers.eq("alice"), org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.eq(100));
    }

    @Test
    void dispatchExceptionSuspendsCurrentAndPendingNodesUntilReconciled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        TaskExecutionService executions = mock(TaskExecutionService.class);
        Task failedTask = new Task();
        failedTask.setId(11L);
        failedTask.setName("事实表加工");
        failedTask.setTaskType("sql");
        failedTask.setStatus("enabled");
        when(tasks.getById(11L)).thenReturn(failedTask);
        when(executions.executePipeline(any()))
                .thenThrow(new IllegalStateException("执行依赖不可用"));
        doReturn(1).when(jdbc).update(any(String.class), any(Object[].class));

        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), executions, new SyncTaskExecutor(), 120, 120);
        var authority=trust(service,"2026-09-08","admin");

        service.executeRun(7L, "2026-09-08", "admin", List.of(11L, 12L));

        verify(authority).finish(any(),eq("unknown"),eq(11L),org.mockito.ArgumentMatchers.contains("待核对"));
        verify(authority,never()).prepare(any(),eq(12L));
    }

    @Test
    void executionPreflightFailureMarksCurrentNodeRetryable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        when(tasks.getById(11L)).thenReturn(task(11L, "恢复后已停用", "spark", "disabled"));
        doReturn(1).when(jdbc).update(any(String.class), any(Object[].class));
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), mock(TaskExecutionService.class),
                new SyncTaskExecutor(), 120, 120);
        var authority=trust(service,"2026-09-09","alice");

        service.executeRun(7L, "2026-09-09", "alice", List.of(11L, 12L));

        verify(authority).finish(any(),eq("failed"),eq(11L),org.mockito.ArgumentMatchers.contains("未提交"));
        verify(authority,never()).prepare(any(),anyLong());
    }

    @Test
    void nodeMessagesAreBoundedToDatabaseColumn() {
        String oversized = "x".repeat(800);
        assertEquals(512, PipelineExecutionService.bounded(oversized, 512).length());
        assertEquals("short", PipelineExecutionService.bounded("short", 512));
    }

    @Test
    void retryRunsOnlyTheSelectedNodeAndItsDownstreamBranches() {
        assertEquals(List.of(12L, 14L), PipelineExecutionService.resumeOrder(
                List.of(10L, 11L, 12L, 13L, 14L), Set.of(12L, 14L), 12L));
    }

    @Test
    void retryRejectsNodeRemovedFromCurrentDependencyGraph() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PipelineExecutionService.resumeOrder(List.of(10L, 11L), Set.of(12L), 12L));

        assertTrue(error.getMessage().contains("已不在当前任务依赖图"));
    }

    @Test
    void retryRejectsUnknownOutcomeToPreventDuplicateRemoteSubmission() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PipelineExecutionService.validateRetrySource("unknown", "unknown"));

        assertTrue(error.getMessage().contains("确认远端执行结果"));
    }

    @Test
    void retryRequiresAnActuallyFailedOrCancelledNode() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PipelineExecutionService.validateRetrySource("failed", "skipped"));

        assertTrue(error.getMessage().contains("失败或已确认取消的节点"));
    }

    @Test
    void reconciledRemoteResultsMapOnlyProvenTerminalStates() {
        assertEquals("succeeded", PipelineExecutionService.reconciledNodeStatus("success"));
        assertEquals("failed", PipelineExecutionService.reconciledNodeStatus("failed"));
        assertEquals("failed", PipelineExecutionService.reconciledNodeStatus("timed_out"));
        assertEquals("cancelled", PipelineExecutionService.reconciledNodeStatus("cancelled"));
        assertThrows(IllegalArgumentException.class,
                () -> PipelineExecutionService.reconciledNodeStatus("running"));
        assertThrows(IllegalArgumentException.class,
                () -> PipelineExecutionService.reconciledNodeStatus("unknown"));
    }

    @Test
    void recoveryDoesNotReadTheLatestInstanceOrReconstructAUsername() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, mock(TaskService.class), mock(TaskInstanceService.class),
                mock(TaskExecutionService.class), new SyncTaskExecutor(), 120, 120);
        var authority=mock(PipelineExecutionStore.class);service.installPipelineAuthority(authority);
        when(authority.recover()).thenReturn(List.of());service.reconcileResolvedNodes();
        verify(authority).recover();org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void recoveredRunsAreOnlyDispatchedAfterTheRecoveryTransactionCommits() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var executor=mock(org.springframework.core.task.TaskExecutor.class);
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, mock(TaskService.class), mock(TaskInstanceService.class),
                mock(TaskExecutionService.class), executor, 120, 120);
        var authority=mock(PipelineExecutionStore.class);service.installPipelineAuthority(authority);when(authority.recover()).thenReturn(List.of(7L));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try{service.reconcileResolvedNodes();org.mockito.Mockito.verifyNoInteractions(executor);
            var callbacks=org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();assertEquals(1,callbacks.size());callbacks.getFirst().afterCommit();verify(executor).execute(any());
        }finally{org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
    }

    @Test
    void staleRunsReleaseActiveRowsAndFinalizeNodes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("RETURNING id"),
                org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(List.of(7L));
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, mock(TaskService.class), mock(TaskInstanceService.class),
                mock(TaskExecutionService.class), new SyncTaskExecutor(), 120, 120);

        service.reconcileStaleRuns();

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status='unknown'"),
                org.mockito.ArgumentMatchers.eq(7L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status='skipped'"),
                org.mockito.ArgumentMatchers.eq(7L));
    }

    @Test
    void terminalUnknownSparkResultSuspendsPipelineAndSkipsDownstream() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        TaskExecutionService executions = mock(TaskExecutionService.class);
        Task spark = new Task();
        spark.setId(11L);
        spark.setName("订单清洗");
        spark.setTaskType("spark");
        spark.setStatus("enabled");
        spark.setTimeoutSeconds(120);
        TaskInstance unknown = new TaskInstance();
        unknown.setId(21L);
        unknown.setStatus("unknown");
        unknown.setFinishedAt(OffsetDateTime.now());
        unknown.setErrorMessage("远端结果无法确认");
        when(tasks.getById(11L)).thenReturn(spark);
        when(executions.executePipeline(any())).thenReturn(unknown);
        doReturn(1).when(jdbc).update(any(String.class), any(Object[].class));
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), executions,
                new SyncTaskExecutor(), 120, 120);
        var authority=trust(service,"2026-09-09","admin");
        service.executeRun(7L, "2026-09-09", "admin", List.of(11L, 12L));
        verify(authority).record(any(),eq(11L),any(),eq(unknown),eq("unknown"),org.mockito.ArgumentMatchers.isNull());
        verify(authority).finish(any(),eq("unknown"),eq(11L),org.mockito.ArgumentMatchers.contains("待核对"));
        verify(authority,never()).prepare(any(),eq(12L));
    }

    @Test
    void confirmedSparkCancellationCancelsPipelineAndSkipsDownstream() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        TaskExecutionService executions = mock(TaskExecutionService.class);
        Task spark = task(11L, "订单清洗", "spark", "enabled");
        spark.setTimeoutSeconds(120);
        TaskInstance cancelled = new TaskInstance();
        cancelled.setId(21L);
        cancelled.setStatus("cancelled");
        cancelled.setFinishedAt(OffsetDateTime.now());
        when(tasks.getById(11L)).thenReturn(spark);
        when(executions.executePipeline(any())).thenReturn(cancelled);
        doReturn(1).when(jdbc).update(any(String.class), any(Object[].class));
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), executions,
                new SyncTaskExecutor(), 120, 120);
        var authority=trust(service,"2026-09-09","admin");
        service.executeRun(7L, "2026-09-09", "admin", List.of(11L, 12L));
        verify(authority).record(any(),eq(11L),any(),eq(cancelled),eq("cancelled"),org.mockito.ArgumentMatchers.isNull());
        verify(authority).finish(any(),eq("cancelled"),eq(11L),org.mockito.ArgumentMatchers.contains("已确认取消"));
        verify(authority,never()).prepare(any(),eq(12L));
    }

    private static PipelineExecutionStore trust(PipelineExecutionService service,String date,String user){
        var authority=mock(PipelineExecutionStore.class);service.installPipelineAuthority(authority);
        when(authority.begin(7)).thenReturn(new PipelineExecutionStore.Dispatch(7,1,date,user,List.of(11L,12L)));
        when(authority.prepare(any(),anyLong())).thenReturn(mock(WorkflowExecutionStore.Claim.class));
        when(authority.record(any(),anyLong(),any(),any(),any(),any())).thenReturn(true);return authority;
    }

    @Test void publicPipelineResponsesDoNotExposeTheSealedSqlOrApprovalIdentity(){
        var row=new java.util.HashMap<String,Object>();row.put("id",7L);row.put("plan_json","private SQL");row.put("invocation_json","private UID/AV");row.put("worker_id","private worker");row.put("plan_sha256","a".repeat(64));
        assertEquals(java.util.Map.of("id",7L,"plan_sha256","a".repeat(64)),PipelineExecutionService.publicRun(row));
        var node=new java.util.HashMap<String,Object>();node.put("id",9L);node.put("definition_json","private SQL");node.put("approval_json","private approver");node.put("dependency_evidence","private source");
        assertEquals(java.util.Map.of("id",9L),PipelineExecutionService.publicNode(node));
        assertTrue(row.containsKey("plan_json"));assertTrue(node.containsKey("definition_json"));
    }
    @Test void missingTrustedStoreCannotFallBackToTheOldUsernameBridge(){
        var executions=mock(TaskExecutionService.class);var service=new PipelineExecutionService(mock(JdbcTemplate.class),mock(TaskService.class),mock(TaskInstanceService.class),executions,new SyncTaskExecutor(),120,120);
        assertThrows(IllegalStateException.class,()->service.executeRun(7,"2026-10-25","admin",List.of(11L)));org.mockito.Mockito.verifyNoInteractions(executions);
    }

    private static Task task(long id, String name, String type, String status) {
        Task task = new Task();
        task.setId(id);
        task.setName(name);
        task.setTaskType(type);
        task.setStatus(status);
        return task;
    }
}
