package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.*;
import com.aetheris.workflow.fixedexecution.FixedJson;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualityGateEvidenceTest {
    static final OffsetDateTime FINISHED=OffsetDateTime.parse("2026-10-25T14:00:00Z");
    static QualityExecutionClient.QualityResult result(String status,long rule,Long count,Long threshold){
        return new QualityExecutionClient.QualityResult(9L,rule,status,count,threshold,12L,"fixture",FINISHED);
    }
    @Test void onlyCompleteConsistentResultsForTheRequestedRuleAreAccepted(){
        assertEquals("failed",QualityGateEvidence.result(7L,result("failed",7,2L,1L)).get("status"));
        assertEquals("passed",QualityGateEvidence.result(7L,result("passed",7,1L,1L)).get("status"));
        for(var bad:List.of(result("unknown",7,null,1L),result("error",7,null,1L),result("failed",8,2L,1L),
                result("passed",7,2L,1L),result("failed",7,1L,1L),result("failed",7,-1L,1L),result("failed",7,2L,-1L),
                new QualityExecutionClient.QualityResult(null,7L,"failed",2L,1L,12L,"fixture",FINISHED),
                new QualityExecutionClient.QualityResult(9L,7L,"failed",2L,1L,12L,"fixture",null))){
            assertThrows(IllegalStateException.class,()->QualityGateEvidence.result(7L,bad));
        }
    }
    @Test void currentFencedFailureIsRequiredAndHistoricalOrMalformedEvidenceIsRejected(){
        var jdbc=mock(JdbcTemplate.class);var task=new Task();task.setId(11L);task.setTaskType("quality");task.setQualityRuleId(7L);
        var instance=new TaskInstance();instance.setId(21L);instance.setStatus("failed");instance.setFinishedAt(FINISHED);instance.setActiveAttemptId(31L);instance.setFencingToken(2L);
        var evidence=QualityGateEvidence.result(7L,result("failed",7,2L,1L));String valid=new String(FixedJson.bytes(evidence),java.nio.charset.StandardCharsets.UTF_8);
        String sql="instance.active_attempt_id=attempt.id";
        when(jdbc.queryForList(contains(sql),eq(String.class),eq(21L),eq(11L),eq(31L),eq(2L))).thenReturn(List.of(valid));
        assertTrue(QualityGateEvidence.confirmedFailure(jdbc,task,instance));
        for(var rows:List.of(List.<String>of(),List.of("{}"),List.of(valid,valid),List.of(valid.replace("\"ruleId\":7","\"ruleId\":8")),List.of(valid.replace("\"failed\"","\"passed\"")))){
            when(jdbc.queryForList(contains(sql),eq(String.class),eq(21L),eq(11L),eq(31L),eq(2L))).thenReturn(rows);
            assertFalse(QualityGateEvidence.confirmedFailure(jdbc,task,instance));
        }
        when(jdbc.queryForList(contains(sql),eq(String.class),eq(21L),eq(11L),eq(31L),eq(2L))).thenThrow(new IllegalStateException("unavailable"));
        assertFalse(QualityGateEvidence.confirmedFailure(jdbc,task,instance));
    }
    @Test void onlyVerifiedGateFailureUsesAnActiveWaiver(){
        var f=new TaskExecutionServiceTest.Fixture();f.edge();var task=new Task();task.setId(2L);task.setTaskType("quality");task.setQualityRuleId(7L);when(f.tasks.getById(2L)).thenReturn(task);
        var upstream=new TaskInstance();upstream.setId(21L);upstream.setStatus("failed");upstream.setFinishedAt(FINISHED);upstream.setActiveAttemptId(31L);upstream.setFencingToken(2L);when(f.instances.getOne(any())).thenReturn(upstream);
        String json=new String(FixedJson.bytes(QualityGateEvidence.result(7L,result("failed",7,2L,1L))),java.nio.charset.StandardCharsets.UTF_8);
        when(f.jdbc.queryForList(contains("instance.active_attempt_id=attempt.id"),eq(String.class),eq(21L),eq(2L),eq(31L),eq(2L))).thenReturn(List.of(json));
        when(f.jdbc.queryForObject(contains("quality_waiver"),eq(Integer.class),eq(2L),eq("2026-10-25"))).thenReturn(1);
        assertNull(f.service.findUnmetDependency(1L,"2026-10-25","pipeline"));
        when(f.jdbc.queryForObject(contains("quality_waiver"),eq(Integer.class),eq(2L),eq("2026-10-25"))).thenReturn(0);
        assertNotNull(f.service.findUnmetDependency(1L,"2026-10-25","pipeline"));
    }
    static class InvocationFixture {
        final ExecutionClaimCoordinator coordinator=mock(ExecutionClaimCoordinator.class);
        final QualityExecutionClient quality=mock(QualityExecutionClient.class);
        final TaskExecutionService service;final ExecutionClaimCoordinator.ExecutionClaim claim;
        InvocationFixture(){
            var tasks=mock(TaskService.class);var task=new Task();task.setId(11L);task.setTaskType("quality");task.setQualityRuleId(7L);task.setStatus("enabled");when(tasks.getById(11L)).thenReturn(task);
            var deps=mock(TaskDependencyService.class);when(deps.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
            var instance=new TaskInstance();instance.setId(21L);instance.setTaskId(11L);instance.setStatus("running");instance.setTriggeredBy("alice");instance.setActiveAttemptId(31L);instance.setFencingToken(2L);
            claim=new ExecutionClaimCoordinator.ExecutionClaim(instance,31L,1,2L,FINISHED.plusDays(1));
            when(coordinator.claim(eq(task),eq("2026-10-25"),eq("pipeline"),eq("alice"),anyInt())).thenReturn(claim);
            when(coordinator.submissionUnknown(eq(claim),anyString())).thenReturn(instance);
            when(coordinator.completeRemote(eq(claim),any())).thenReturn(instance);
            service=new TaskExecutionService(tasks,mock(TaskInstanceService.class),mock(TaskLogService.class),deps,coordinator,mock(SqlExecutionClient.class),mock(NotificationClient.class),mock(SparkBatchExecutionService.class),quality,mock(LineageEvidenceClient.class),mock(JdbcTemplate.class));
        }
        // Exercise the legacy result adapter directly; it is no longer an authorized production entry.
        void execute(){org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"runQuality",qualityTask(),claim);}
        Task qualityTask(){var task=new Task();task.setId(11L);task.setTaskType("quality");task.setQualityRuleId(7L);return task;}
    }
    @Test void missingErrorAndMalformedQualityResponsesStayUnknownAndCannotBecomeWaivableFailures(){
        for(var response:Arrays.asList(null,result("unknown",7,null,1L),result("error",7,null,1L),result("passed",7,2L,1L),result("failed",8,2L,1L))){
            var f=new InvocationFixture();when(f.quality.execute(7L,"alice")).thenReturn(response);f.execute();
            verify(f.coordinator).submissionUnknown(eq(f.claim),anyString());verify(f.coordinator,never()).completeRemote(any(),any());
            verify(f.coordinator,never()).fail(any(),anyString(),anyLong(),anyLong());
        }
        var f=new InvocationFixture();when(f.quality.execute(7L,"alice")).thenThrow(new IllegalStateException("transport lost"));f.execute();
        verify(f.coordinator).submissionUnknown(eq(f.claim),anyString());
    }
    @Test void completedQualityResponsesPersistExactNumericEvidenceWithoutRemoteErrorText(){
        for(String status:List.of("passed","failed")){
            var f=new InvocationFixture();when(f.quality.execute(7L,"alice")).thenReturn(result(status,7,"passed".equals(status)?1L:2L,1L));f.execute();
            var captured=org.mockito.ArgumentCaptor.forClass(ExecutionAdapter.RemoteExecution.class);verify(f.coordinator).completeRemote(eq(f.claim),captured.capture());
            var remote=captured.getValue();assertEquals("passed".equals(status)?"succeeded":"failed",remote.status());
            assertEquals(9L,remote.result().get("resultId"));assertEquals(7L,remote.result().get("ruleId"));assertEquals(status,remote.result().get("status"));
            if(remote.errorMessage()!=null)assertFalse(remote.errorMessage().contains("fixture"));
        }
    }
}
