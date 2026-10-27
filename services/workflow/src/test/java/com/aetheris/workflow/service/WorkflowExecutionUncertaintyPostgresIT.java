package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.*;
import com.aetheris.workflow.fixedexecution.FixedJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real state/constraint checks on a fresh harness-owned database; remote workers are controlled peers. */
class WorkflowExecutionUncertaintyPostgresIT {
    JdbcTemplate jdbc;DriverManagerDataSource source;DataSourceTransactionManager manager;ExecutionClaimCoordinator coordinator;
    @BeforeEach void connect(){
        String url=System.getenv("AETHERIS_WORKFLOW_UNCERTAINTY_URL");assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_api_key_audit"));
        String run=System.getenv("AETHERIS_WORKFLOW_UNCERTAINTY_RUN");assertNotNull(run);assertTrue(run.matches("[a-f0-9]{32}"));
        source=new DriverManagerDataSource(url,"aetheris_workflow",Objects.requireNonNull(System.getenv("AETHERIS_WORKFLOW_UNCERTAINTY_PASSWORD")));
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);coordinator=new ExecutionClaimCoordinator(jdbc,manager,new ObjectMapper(),"uncertainty-"+run,60,8);
        assertEquals("aetheris_workflow",jdbc.queryForObject("SELECT current_user",String.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForList("SELECT * FROM auth.auth_user"));
    }
    Task quality(){long id=jdbc.queryForObject("INSERT INTO workflow.task(name,task_type,quality_rule_id) VALUES(?,'quality',77) RETURNING id",Long.class,"uncertainty-"+UUID.randomUUID());return task(id);}
    Task task(long id){return jdbc.queryForObject("SELECT * FROM workflow.task WHERE id=?",new BeanPropertyRowMapper<>(Task.class),id);}
    TaskService tasks(){var tasks=mock(TaskService.class);when(tasks.getById(any())).thenAnswer(call->task(((Number)call.getArgument(0)).longValue()));return tasks;}
    ExecutionClaimCoordinator.ExecutionClaim claim(Task task){return coordinator.claim(task,"2026-10-25","pipeline","audit-state-worker",30);}
    long pipeline(Task first,Task next){
        long id=jdbc.queryForObject("INSERT INTO workflow.pipeline_run(target_task_id,biz_date,triggered_by) VALUES(?,'2026-10-25','audit-state-worker') RETURNING id",Long.class,next.getId());
        jdbc.update("INSERT INTO workflow.pipeline_node_run(pipeline_run_id,task_id,sequence_no) VALUES(?,?,1),(?,?,2)",id,first.getId(),id,next.getId());return id;
    }
    void waiver(Task task){jdbc.update("INSERT INTO workflow.quality_waiver(quality_task_id,biz_date,reason,approved_by,expires_at) VALUES(?,'2026-10-25','fixture','audit-state-worker',now()+interval '1 hour')",task.getId());}
    void assertSuspended(long run,Task next){
        assertEquals("unknown",jdbc.queryForObject("SELECT status FROM workflow.pipeline_run WHERE id=?",String.class,run));
        assertEquals("skipped",jdbc.queryForObject("SELECT status FROM workflow.pipeline_node_run WHERE pipeline_run_id=? AND task_id=?",String.class,run,next.getId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM workflow.task_instance WHERE task_id=?",Integer.class,next.getId()));
    }
    @Test void qualityFailureEvidenceMatchesTheActualFencedAttempt(){
        Task task=quality();var claim=claim(task);var result=new QualityExecutionClient.QualityResult(9L,77L,"failed",2L,1L,1L,"fixture",OffsetDateTime.now());
        var evidence=QualityGateEvidence.result(77L,result);TaskInstance failed=coordinator.completeRemote(claim,new ExecutionAdapter.RemoteExecution(null,"failed",null,2L,"QUALITY_GATE_FAILED","fixture",evidence,null));
        assertTrue(QualityGateEvidence.confirmedFailure(jdbc,task,failed));
        jdbc.update("UPDATE workflow.execution_attempt SET result_metadata_json='{}'::jsonb WHERE id=?",claim.attemptId());
        assertFalse(QualityGateEvidence.confirmedFailure(jdbc,task,failed));
        jdbc.update("UPDATE workflow.execution_attempt SET result_metadata_json=?::jsonb WHERE id=?",new String(FixedJson.bytes(evidence),java.nio.charset.StandardCharsets.UTF_8),claim.attemptId());
        var next=coordinator.claim(task,"2026-10-25","pipeline","audit-state-worker",30);
        assertFalse(QualityGateEvidence.confirmedFailure(jdbc,task,failed));assertTrue(next.fencingToken()>claim.fencingToken());
    }
    @Test void anActiveWaiverCannotAdvanceAnUnknownQualityNode(){
        Task first=quality(),next=quality();var claim=claim(first);TaskInstance unknown=coordinator.submissionUnknown(claim,"controlled lost result");waiver(first);long run=pipeline(first,next);
        assertNotNull(dependencyPolicy(first,next,unknown).findUnmetDependency(next.getId(),"2026-10-25","pipeline"));
        assertFalse(QualityGateEvidence.confirmedFailure(jdbc,first,unknown));
        assertEquals("unknown",jdbc.queryForObject("SELECT status FROM workflow.execution_attempt WHERE id=?",String.class,claim.attemptId()));
        assertThrows(IllegalStateException.class,()->claim(first));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM workflow.task_instance WHERE task_id=?",Integer.class,next.getId()));
    }
    @Test void completedFailureWithEvidenceCanStillUseAnExplicitWaiver(){
        Task first=quality(),next=quality();var claim=claim(first);var evidence=QualityGateEvidence.result(77L,new QualityExecutionClient.QualityResult(10L,77L,"failed",2L,1L,1L,"fixture",OffsetDateTime.now()));
        TaskInstance failed=coordinator.completeRemote(claim,new ExecutionAdapter.RemoteExecution(null,"failed",null,2L,"QUALITY_GATE_FAILED","fixture",evidence,null));
        waiver(first);assertTrue(QualityGateEvidence.confirmedFailure(jdbc,first,failed));
        assertNull(dependencyPolicy(first,next,failed).findUnmetDependency(next.getId(),"2026-10-25","pipeline"));
        jdbc.update("UPDATE workflow.quality_waiver SET expires_at=clock_timestamp()-interval '1 day',created_at=clock_timestamp()-interval '2 days' WHERE quality_task_id=?",first.getId());
        assertNotNull(dependencyPolicy(first,next,failed).findUnmetDependency(next.getId(),"2026-10-25","pipeline"));
    }
    TaskExecutionService dependencyPolicy(Task first,Task next,TaskInstance result){
        var instances=mock(TaskInstanceService.class);when(instances.getOne(any())).thenReturn(result);
        var dependencies=mock(TaskDependencyService.class);var edge=new TaskDependency();edge.setTaskId(next.getId());edge.setUpstreamTaskId(first.getId());when(dependencies.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(edge));
        return new TaskExecutionService(tasks(),instances,mock(TaskLogService.class),dependencies,coordinator,mock(SqlExecutionClient.class),mock(NotificationClient.class),mock(SparkBatchExecutionService.class),mock(QualityExecutionClient.class),mock(LineageEvidenceClient.class),jdbc);
    }
    @Test void unknownCancellationKeepsRunnerCapacityUntilPhysicalTerminationIsConfirmed(){
        long job=jdbc.queryForObject("SELECT min(id) FROM workflow.spark_job_definition",Long.class);
        long id=jdbc.queryForObject("INSERT INTO workflow.task(name,task_type,job_definition_id) VALUES(?,'spark',?) RETURNING id",Long.class,"uncertain-spark-"+UUID.randomUUID(),job);
        Task task=task(id);var claim=claim(task);
        assertEquals("queued",jdbc.queryForObject("SELECT status FROM workflow.task_instance WHERE id=?",String.class,claim.instance().getId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE workflow.task_instance SET status='unrecognised' WHERE id=?",claim.instance().getId()));
        coordinator.submitted(claim,new ExecutionAdapter.ManagedHandle("controlled-handle","fixture-key","running",null,null));
        Map<String,Object> runner=jdbc.queryForMap("SELECT id,environment_id FROM workflow.compute_runner WHERE base_url='config://default'");long runnerId=((Number)runner.get("id")).longValue();
        jdbc.update("UPDATE workflow.execution_attempt SET runner_id=? WHERE id=?",runnerId,claim.attemptId());
        jdbc.update("INSERT INTO workflow.spark_resource_allocation(attempt_id,environment_id,runner_id,requested_cores,requested_memory_mb,status,admitted_at) VALUES(?,?,?,1,768,'admitted',now())",claim.attemptId(),runner.get("environment_id"),runnerId);
        var adapter=mock(SparkBatchExecutionAdapter.class);var queue=new SparkResourceQueueService(jdbc,manager,mock(ManagedSparkCatalogService.class),adapter);
        when(adapter.logs(anyString(),anyString(),anyLong(),anyInt())).thenReturn(new ExecutionAdapter.LogPage("",0,false));
        var service=new SparkBatchExecutionService(mock(ManagedSparkCatalogService.class),adapter,coordinator,mock(TaskLogService.class),jdbc,mock(LineageEvidenceClient.class),queue);
        when(adapter.cancel("config://default","controlled-handle")).thenReturn(new ExecutionAdapter.RemoteExecution("controlled-handle","unknown",null,null,"CANCEL_UNKNOWN","fixture",Map.of(),null));
        assertEquals("unknown",service.cancel(claim.instance().getId()).getStatus());queue.releaseTerminalAllocations();
        assertEquals("admitted",jdbc.queryForObject("SELECT status FROM workflow.spark_resource_allocation WHERE attempt_id=?",String.class,claim.attemptId()));
        assertThrows(IllegalStateException.class,()->claim(task));
        when(adapter.cancel("config://default","controlled-handle")).thenReturn(new ExecutionAdapter.RemoteExecution("controlled-handle","cancelled",null,null,null,null,Map.of(),OffsetDateTime.now().toString()));
        assertEquals("cancelled",service.cancel(claim.instance().getId()).getStatus());
        assertEquals("released",jdbc.queryForObject("SELECT status FROM workflow.spark_resource_allocation WHERE attempt_id=?",String.class,claim.attemptId()));
    }
}
