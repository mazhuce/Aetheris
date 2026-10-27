package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.*;
import com.aetheris.workflow.fixedexecution.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uncertain physical work must neither advance the DAG nor free its capacity. */
class ExecutionUncertaintyRegressionTest {
    static class PipelineFixture {
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final TaskService tasks=mock(TaskService.class);
        final TaskInstanceService instances=mock(TaskInstanceService.class);
        final TaskExecutionService executions=mock(TaskExecutionService.class);
        final PipelineExecutionStore authority=mock(PipelineExecutionStore.class);
        final PipelineExecutionStore.Dispatch dispatch=new PipelineExecutionStore.Dispatch(7,1,"2026-10-25","alice",List.of(11L,12L));
        final WorkflowExecutionStore.Claim claim=mock(WorkflowExecutionStore.Claim.class);
        final TaskInstance result=new TaskInstance();
        final PipelineExecutionService service=new PipelineExecutionService(jdbc,tasks,instances,executions,new SyncTaskExecutor(),120,120);
        PipelineFixture(String type,String status) {
            var task=new Task();task.setId(11L);task.setName("uncertain gate");task.setTaskType(type);task.setStatus("enabled");task.setTimeoutSeconds(30);
            when(tasks.getById(11L)).thenReturn(task);
            result.setId(21L);result.setStatus(status);result.setActiveAttemptId(31L);result.setFencingToken(2L);
            if(!"running".equals(status))result.setFinishedAt(OffsetDateTime.now());
            service.installPipelineAuthority(authority);when(authority.begin(7)).thenReturn(dispatch);when(authority.prepare(dispatch,11)).thenReturn(claim);
            when(authority.record(eq(dispatch),eq(11L),eq(claim),any(),anyString(),any())).thenReturn(true);
            when(executions.executePipeline(claim)).thenReturn(result);
            when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
            when(jdbc.query(contains("FROM workflow.quality_waiver"),any(RowMapper.class),eq(11L),eq("2026-10-25"))).thenReturn(List.of(71L));
        }
        void run(){service.executeRun(7L,"2026-10-25","alice",List.of(11L,12L));}
        void assertSuspended(){
            verify(authority).finish(eq(dispatch),eq("unknown"),eq(11L),contains("待核对"));
            verify(authority,never()).prepare(dispatch,12);
            verify(authority,never()).finish(eq(dispatch),eq("succeeded"),any(),any());
        }
    }
    @Test void unknownQualityCannotBeWaivedIntoDownstreamExecution(){
        var f=new PipelineFixture("quality","unknown");f.run();f.assertSuspended();
        verify(f.jdbc,never()).query(contains("FROM workflow.quality_waiver"),any(RowMapper.class),anyLong(),anyString());
    }
    @Test void confirmedCancellationCannotBeWaivedIntoSuccess(){
        var f=new PipelineFixture("quality","cancelled");f.run();
        verify(f.authority).finish(eq(f.dispatch),eq("cancelled"),eq(11L),contains("已确认取消"));
        verify(f.authority,never()).prepare(f.dispatch,12);
    }
    @Test void interruptedRemoteWaitSuspendsInsteadOfMakingTheRunRetryable(){
        var f=new PipelineFixture("spark","running");
        Thread.currentThread().interrupt();
        try{f.run();assertTrue(Thread.currentThread().isInterrupted());f.assertSuspended();}
        finally{Thread.interrupted();}
    }
    @Test void missingRemotePollResultRemainsUncertain(){
        var f=new PipelineFixture("spark","running");when(f.instances.getById(21L)).thenReturn(null);
        f.run();f.assertSuspended();
    }
    @Test void exceptionAfterDispatchIsNotProofThatTheWorkerDidNotRun(){
        var f=new PipelineFixture("quality","running");
        when(f.executions.executePipeline(f.claim)).thenThrow(new IllegalStateException("response lost after submission"));
        f.run();f.assertSuspended();
        verify(f.authority,never()).finish(eq(f.dispatch),eq("failed"),any(),any());
    }
    @Test void nullRemoteStatusIsUncertainRatherThanARetryableFailure(){
        var f=new PipelineFixture("spark","running");var initial=f.result;initial.setStatus(null);
        f.run();f.assertSuspended();
    }
    @Test void timeoutUsesMonotonicTimeAndDoesNotModifyThePhysicalAttempt() throws Exception {
        var f=new PipelineFixture("spark","running");var service=spy(f.service);
        doReturn(0L,java.util.concurrent.TimeUnit.SECONDS.toNanos(91)).when(service).remoteWaitNanos();
        var initial=f.result;
        var task=f.tasks.getById(11L);var result=service.awaitTerminal(task,initial);
        assertEquals("unknown",result.getStatus());assertEquals("running",initial.getStatus());
        assertEquals(21L,result.getId());assertEquals(31L,result.getActiveAttemptId());assertEquals(2L,result.getFencingToken());
        verify(service,never()).pauseRemotePoll();verifyNoInteractions(f.instances);
    }
    @Test void successfulPollFromAnotherAttemptCannotAdvanceThisNode() throws Exception {
        var f=new PipelineFixture("spark","running");var service=spy(f.service);doNothing().when(service).pauseRemotePoll();
        var replacement=new TaskInstance();replacement.setId(21L);replacement.setActiveAttemptId(32L);replacement.setFencingToken(3L);replacement.setStatus("success");
        when(f.instances.getById(21L)).thenReturn(replacement);
        assertEquals("unknown",service.awaitTerminal(f.tasks.getById(11L),f.result).getStatus());
    }
    @Test void confirmedPollForTheSameAttemptStillSucceeds() throws Exception {
        var f=new PipelineFixture("spark","running");var service=spy(f.service);doNothing().when(service).pauseRemotePoll();
        var completed=new TaskInstance();completed.setId(21L);completed.setActiveAttemptId(31L);completed.setFencingToken(2L);completed.setStatus("success");
        when(f.instances.getById(21L)).thenReturn(completed);
        assertSame(completed,service.awaitTerminal(f.tasks.getById(11L),f.result));
    }
    @Test void missingOrUncertainUpstreamCannotUseAWaiver(){
        for(String status:List.of("missing","unknown","running","queued","cancel_requested","cancelled","timed_out")){
            var tasks=mock(TaskService.class);var instances=mock(TaskInstanceService.class);var deps=mock(TaskDependencyService.class);var jdbc=mock(JdbcTemplate.class);
            var edge=new TaskDependency();edge.setTaskId(1L);edge.setUpstreamTaskId(2L);
            when(deps.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(edge));
            var upstreamTask=new Task();upstreamTask.setId(2L);upstreamTask.setName("quality");upstreamTask.setTaskType("quality");when(tasks.getById(2L)).thenReturn(upstreamTask);
            if(!"missing".equals(status)){var upstream=new TaskInstance();upstream.setStatus(status);when(instances.getOne(any())).thenReturn(upstream);}
            when(jdbc.queryForObject(contains("quality_waiver"),eq(Integer.class),eq(2L),eq("2026-10-25"))).thenReturn(1);
            var service=new TaskExecutionService(tasks,instances,mock(TaskLogService.class),deps,mock(ExecutionClaimCoordinator.class),mock(SqlExecutionClient.class),mock(NotificationClient.class),mock(SparkBatchExecutionService.class),mock(QualityExecutionClient.class),mock(LineageEvidenceClient.class),jdbc);
            assertNotNull(service.findUnmetDependency(1L,"2026-10-25","pipeline"),status);
            verifyNoInteractions(jdbc);
        }
    }
    @Test void unknownSparkCancellationKeepsItsPhysicalAllocation() throws Exception {
        var adapter=mock(SparkBatchExecutionAdapter.class);var coordinator=mock(ExecutionClaimCoordinator.class);var queue=mock(SparkResourceQueueService.class);var jdbc=mock(JdbcTemplate.class);
        var row=mock(ResultSet.class);when(row.getLong("instance_id")).thenReturn(21L);when(row.getLong("task_id")).thenReturn(11L);
        when(row.getInt("attempt")).thenReturn(1);when(row.getLong("attempt_id")).thenReturn(31L);when(row.getLong("instance_fencing_token")).thenReturn(2L);
        when(row.getObject("active_attempt_id")).thenReturn(31L);when(row.getString("instance_status")).thenReturn("running");when(row.getString("attempt_status")).thenReturn("running");
        when(row.getString("remote_handle")).thenReturn("remote-31");when(row.getString("runner_base_url")).thenReturn("config://default");
        when(jdbc.query(contains("WHERE instance.id=?"),any(RowMapper.class),eq(21L))).thenAnswer(call->List.of(((RowMapper<?>)call.getArgument(1)).mapRow(row,0)));
        var unknown=new ExecutionAdapter.RemoteExecution("remote-31","unknown",null,null,"CANCEL_UNCONFIRMED","still unconfirmed",Map.of(),null);
        when(adapter.cancel("config://default","remote-31")).thenReturn(unknown);when(adapter.logs(anyString(),anyString(),anyLong(),anyInt())).thenReturn(new ExecutionAdapter.LogPage("",0,false));
        var result=new TaskInstance();result.setId(21L);result.setStatus("unknown");when(coordinator.completeRemote(any(),eq(unknown))).thenReturn(result);
        var service=new SparkBatchExecutionService(mock(ManagedSparkCatalogService.class),adapter,coordinator,mock(TaskLogService.class),jdbc,mock(LineageEvidenceClient.class),queue);
        assertEquals("unknown",service.cancel(21L).getStatus());
        verify(queue,never()).release(anyLong(),anyString());
        var cancelled=new ExecutionAdapter.RemoteExecution("remote-31","cancelled",null,null,null,null,Map.of(),OffsetDateTime.now().toString());
        when(adapter.cancel("config://default","remote-31")).thenReturn(cancelled);result.setStatus("cancelled");
        when(coordinator.completeRemote(any(),eq(cancelled))).thenReturn(result);
        assertEquals("cancelled",service.cancel(21L).getStatus());
        verify(queue).release(31L,"cancelled");
    }
}
