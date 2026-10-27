package com.aetheris.workflow.service;

import com.aetheris.security.protocol.UserProof;
import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.workflow.entity.*;
import com.aetheris.workflow.fixedexecution.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL source/lease/attempt constraints. Identities and completion receipts are controlled fixtures. */
class PipelineSqlAuthorityPostgresIT {
    JdbcTemplate jdbc;DataSourceTransactionManager manager;ExecutionClaimCoordinator coordinator;
    WorkflowExecutionStore sql;PipelineExecutionStore pipelines;TaskService tasks;
    final String date="2026-10-25";
    @BeforeEach void setup(){
        String url=Objects.requireNonNull(System.getenv("AETHERIS_WORKFLOW_UNCERTAINTY_URL"));
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_api_key_audit"));
        String run=Objects.requireNonNull(System.getenv("AETHERIS_WORKFLOW_UNCERTAINTY_RUN"));assertTrue(run.matches("[a-f0-9]{32}"));
        var source=new DriverManagerDataSource(url,"aetheris_workflow",Objects.requireNonNull(System.getenv("AETHERIS_WORKFLOW_UNCERTAINTY_PASSWORD")));
        jdbc=spy(new JdbcTemplate(source));manager=new DataSourceTransactionManager(source);
        coordinator=new ExecutionClaimCoordinator(jdbc,manager,new ObjectMapper(),"pipeline-IT-"+run,60,8);
        tasks=mock(TaskService.class);when(tasks.getById(any())).thenAnswer(c->jdbc.queryForObject("SELECT * FROM workflow.task WHERE id=?",new BeanPropertyRowMapper<>(Task.class),((Number)c.getArgument(0)).longValue()));
        sql=new WorkflowExecutionStore(jdbc,manager,coordinator,tasks);pipelines=new PipelineExecutionStore(jdbc,manager,sql);
        assertEquals("aetheris_workflow",jdbc.queryForObject("SELECT current_user",String.class));
        assertThrows(DataAccessException.class,()->jdbc.queryForList("SELECT * FROM auth.auth_user"));
    }
    TrustedUser user(long uid,long av){var user=mock(TrustedUser.class);when(user.proof()).thenReturn(new UserProof(2,"gateway","workflow",uid,"fixture-user-"+uid,av,1,61,"pipeline-fixture-"+uid));return user;}
    WorkflowActor actor(){return WorkflowActor.pipeline(user(42,3));}
    long task(){return jdbc.queryForObject("INSERT INTO workflow.task(name,task_type,datasource_id,task_sql,timeout_seconds) VALUES(?,'sql',910001,'INSERT INTO business.output SELECT 1',1800) RETURNING id",Long.class,"pipeline-"+UUID.randomUUID());}
    void approve(long id){sql.approve(sql.definition(id),WorkflowActor.manual(user(43,4)));}
    long[] graph(){long a=task(),b=task();jdbc.update("INSERT INTO workflow.task_dependency(task_id,upstream_task_id) VALUES(?,?)",b,a);approve(a);approve(b);return new long[]{a,b};}
    long create(long[] graph){var order=Arrays.stream(graph).boxed().toList();return pipelines.create(graph[graph.length-1],date,actor(),order,order,null,null,120);}
    TaskInstance complete(WorkflowExecutionStore.Claim claim,boolean success){sql.bindReceipt(claim,1000+claim.execution().attemptId());return success?coordinator.completeFixedSql(claim.execution(),new SqlExecutionClient.ExecutionResult(1L,2)):coordinator.failFixedSql(claim.execution(),"controlled failed receipt");}
    void record(PipelineExecutionStore.Dispatch dispatch,long task,WorkflowExecutionStore.Claim claim,TaskInstance result){assertTrue(pipelines.record(dispatch,task,claim,result,"success".equals(result.getStatus())?"succeeded":"failed",null));}
    String state(long run){return jdbc.queryForObject("SELECT status FROM workflow.pipeline_run WHERE id=?",String.class,run);}
    long attempts(long task){return jdbc.queryForObject("SELECT count(*) FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.id=a.instance_id WHERE i.task_id=?",Long.class,task);}
    @Test void approvedPipelineUsesOriginalIdentityAndItsOwnFrozenDependencyAttempts(){
        var graph=graph();long run=create(graph);var dispatch=pipelines.begin(run);assertNull(pipelines.begin(run));
        assertThrows(IllegalStateException.class,()->pipelines.prepare(dispatch,graph[1]));assertEquals(0,attempts(graph[1]));
        var first=pipelines.prepare(dispatch,graph[0]);var source=sql.lookup(first.execution().instance().getId(),first.execution().attemptId(),first.execution().fencingToken());
        var manifest=FixedJson.parse(source.canonicalManifest());assertEquals("pipeline",manifest.at("/actor/kind").asText());
        assertEquals(42,manifest.at("/actor/uid").asLong());assertEquals(3,manifest.at("/actor/accessVersion").asLong());assertTrue(manifest.get("parent").isNull());
        assertTrue(source.leaseExpiresAt()-System.currentTimeMillis()/1000>1700,"SQL budget must not collapse to 120s parent dispatch lease");
        record(dispatch,graph[0],first,complete(first,true));var second=pipelines.prepare(dispatch,graph[1]);record(dispatch,graph[1],second,complete(second,true));
        pipelines.finish(dispatch,"succeeded",null,null);assertEquals("succeeded",state(run));
    }
    @Test void sealedPlansCannotGainNodesOrChangeIdentityDefinitionSequenceOrAttempt(){
        var graph=graph();long run=create(graph);long extra=task();approve(extra);
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO workflow.pipeline_node_run(pipeline_run_id,task_id,sequence_no) VALUES(?,?,3)",run,extra));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.pipeline_run SET invocation_json=jsonb_set(invocation_json,'{uid}','999') WHERE id=?",run));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.pipeline_node_run SET sequence_no=9 WHERE pipeline_run_id=? AND task_id=?",run,graph[0]));
        var dispatch=pipelines.begin(run);var claim=pipelines.prepare(dispatch,graph[0]);
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET pipeline_node_id=NULL WHERE id=?",claim.execution().attemptId()));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.pipeline_node_run SET attempt_id=NULL,fencing_token=NULL WHERE pipeline_run_id=? AND task_id=?",run,graph[0]));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET invocation_json=jsonb_set(invocation_json,'{kind}','\"manual\"') WHERE id=?",claim.execution().attemptId()));
    }
    @Test void aPipelineActorWithoutAParentBindingCannotBorrowTheManualEntry(){
        long id=task();approve(id);assertThrows(DataAccessException.class,()->sql.claimManual(id,date,actor()));assertEquals(0,attempts(id));
        var manual=sql.claimManual(id,date,WorkflowActor.manual(user(42,3)));
        assertEquals("manual",FixedJson.parse(sql.lookup(manual.execution().instance().getId(),manual.execution().attemptId(),manual.execution().fencingToken()).canonicalManifest()).at("/actor/kind").asText());
    }
    @Test void concurrentNodeInsertionCannotCommitAfterTheParentPlanWasSealed() throws Exception {
        long task=task(),extra=task();approve(task);approve(extra);long original=create(new long[]{task});
        jdbc.update("UPDATE workflow.pipeline_run SET status='cancelled',finished_at=clock_timestamp(),lease_expires_at=NULL WHERE id=?",original);
        long run=jdbc.queryForObject("INSERT INTO workflow.pipeline_run(target_task_id,biz_date,triggered_by) VALUES(?,?,'fixture-user-42') RETURNING id",Long.class,task,date);
        jdbc.update("INSERT INTO workflow.pipeline_node_run(pipeline_run_id,task_id,sequence_no,execution_revision,definition_sha256,definition_json,approval_json,dependency_evidence) SELECT ?,task_id,sequence_no,execution_revision,definition_sha256,definition_json,approval_json,dependency_evidence FROM workflow.pipeline_node_run WHERE pipeline_run_id=?",run,original);
        String application="pipeline-insert-"+UUID.randomUUID();var executor=Executors.newSingleThreadExecutor();
        var future=new java.util.concurrent.atomic.AtomicReference<Future<String>>();
        try{
            new TransactionTemplate(manager).executeWithoutResult(tx->{
                jdbc.queryForObject("SELECT id FROM workflow.pipeline_run WHERE id=? FOR UPDATE",Long.class,run);
                future.set(executor.submit(()->{
                    try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){
                        try(var set=connection.prepareStatement("SELECT set_config('application_name',?,false)")){set.setString(1,application);set.execute();}
                        try(var insert=connection.prepareStatement("INSERT INTO workflow.pipeline_node_run(pipeline_run_id,task_id,sequence_no) VALUES(?,?,2)")){insert.setLong(1,run);insert.setLong(2,extra);insert.execute();}
                        return "unexpectedly-allowed";
                    }catch(java.sql.SQLException denied){return denied.getSQLState();}
                }));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<deadline&&!waiting){
                    // The observer transaction must not reuse its first pg_stat_activity snapshot.
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock')",Boolean.class,application));
                    if(!waiting&&future.get().isDone())try{fail("insertion returned before parent seal: "+future.get().get());}catch(Exception failed){throw new IllegalStateException(failed);}
                    if(!waiting)try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                }
                assertTrue(waiting,"node insertion must wait for the parent seal lock");
                assertEquals(1,jdbc.update("UPDATE workflow.pipeline_run target SET invocation_json=source.invocation_json,plan_json=source.plan_json,plan_sha256=source.plan_sha256 FROM workflow.pipeline_run source WHERE target.id=? AND source.id=?",run,original));
            });
            assertEquals("55000",future.get().get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM workflow.pipeline_node_run WHERE pipeline_run_id=?",Integer.class,run));
        }finally{executor.shutdownNow();}
    }
    @Test void definitionOrDependencyChangesInvalidateSourceAndPreventTheNextClaim(){
        var graph=graph();long run=create(graph);var dispatch=pipelines.begin(run);var first=pipelines.prepare(dispatch,graph[0]);
        jdbc.update("UPDATE workflow.task SET task_sql='INSERT INTO business.output SELECT 2' WHERE id=?",graph[0]);
        assertThrows(IllegalStateException.class,()->sql.lookup(first.execution().instance().getId(),first.execution().attemptId(),first.execution().fencingToken()));
        assertThrows(IllegalStateException.class,()->pipelines.prepare(dispatch,graph[1]));assertEquals(0,attempts(graph[1]));
        var next=graph();long otherRun=create(next);var other=pipelines.begin(otherRun);var source=pipelines.prepare(other,next[0]);long edge=task();approve(edge);
        jdbc.update("INSERT INTO workflow.task_dependency(task_id,upstream_task_id) VALUES(?,?)",next[1],edge);
        assertThrows(IllegalStateException.class,()->sql.lookup(source.execution().instance().getId(),source.execution().attemptId(),source.execution().fencingToken()));
    }
    @Test void anotherRunsOrManualSuccessCannotSatisfyThisPlansDependencies(){
        var graph=graph();long run=create(graph);var outside=sql.claimManual(graph[0],date,WorkflowActor.manual(user(42,3)));complete(outside,true);
        var dispatch=pipelines.begin(run);assertThrows(IllegalStateException.class,()->pipelines.prepare(dispatch,graph[1]));assertEquals(0,attempts(graph[1]));
        var first=pipelines.prepare(dispatch,graph[0]);assertTrue(first.execution().fencingToken()>outside.execution().fencingToken());
        record(dispatch,graph[0],first,complete(first,true));assertNotNull(pipelines.prepare(dispatch,graph[1]));
    }
    @Test void crashedWorkerRecoversOnlyOneDispatchFromTheOriginalFinishedAttempt() throws Exception {
        var graph=graph();long run=create(graph);var old=pipelines.begin(run);var first=pipelines.prepare(old,graph[0]);complete(first,true);
        jdbc.update("UPDATE workflow.pipeline_run SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",run);
        var service=new PipelineExecutionService(jdbc,tasks,mock(TaskInstanceService.class),mock(TaskExecutionService.class),new SyncTaskExecutor(),120,120);
        new TransactionTemplate(manager).executeWithoutResult(tx->service.reconcileStaleRuns());assertEquals("unknown",state(run));
        var other=new PipelineExecutionStore(jdbc,manager,sql);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->{start.await();return pipelines.recover();});var b=pool.submit(()->{start.await();return other.recover();});start.countDown();
            var ids=new ArrayList<Long>();ids.addAll(a.get(15,TimeUnit.SECONDS));ids.addAll(b.get(15,TimeUnit.SECONDS));assertEquals(List.of(run),ids);
        }finally{pool.shutdownNow();}
        var resumed=other.begin(run);assertTrue(resumed.fence()>old.fence());assertEquals(List.of(graph[1]),resumed.order());
        assertThrows(IllegalStateException.class,()->pipelines.prepare(old,graph[1]));assertEquals(0,attempts(graph[1]));
        var second=other.prepare(resumed,graph[1]);assertEquals(42,FixedJson.parse(sql.lookup(second.execution().instance().getId(),second.execution().attemptId(),second.execution().fencingToken()).canonicalManifest()).at("/actor/uid").asLong());
    }
    @Test void aLostFinalRunWriteCanRecoverAfterEveryOriginalNodeWasAlreadyRecorded(){
        long task=task();approve(task);long run=create(new long[]{task});var dispatch=pipelines.begin(run);var claim=pipelines.prepare(dispatch,task);record(dispatch,task,claim,complete(claim,true));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("controlled final-write failure")).doCallRealMethod()
                .when(jdbc).update(contains("UPDATE workflow.pipeline_run SET status=?,error_message=?,finished_at=clock_timestamp()"),any(Object[].class));
        assertThrows(DataAccessException.class,()->pipelines.finish(dispatch,"succeeded",null,null));assertEquals("running",state(run));
        pipelines.finish(dispatch,"unknown",task,"completion response lost");assertEquals("unknown",state(run));
        assertTrue(pipelines.recover().isEmpty());assertEquals("succeeded",state(run));assertEquals(1,attempts(task));
    }
    @Test void aNewAttemptCannotResolveTheOldNodeAndUnknownRunsCannotBeStartedAgain(){
        var graph=graph();long run=create(graph);var dispatch=pipelines.begin(run);var claim=pipelines.prepare(dispatch,graph[0]);complete(claim,true);
        pipelines.finish(dispatch,"unknown",graph[0],"response lost");
        assertThrows(IllegalStateException.class,()->create(graph));
        var replacement=sql.claimManual(graph[0],date,WorkflowActor.manual(user(42,3)));complete(replacement,true);
        assertTrue(pipelines.recover().isEmpty());assertEquals("unknown",state(run));assertEquals(0,attempts(graph[1]));
    }
    @Test void explicitRetryUsesNewVerifiedActorAndExactOriginalDependencyReceipts(){
        var graph=graph();long run=create(graph);var dispatch=pipelines.begin(run);var first=pipelines.prepare(dispatch,graph[0]);record(dispatch,graph[0],first,complete(first,true));
        var second=pipelines.prepare(dispatch,graph[1]);record(dispatch,graph[1],second,complete(second,false));pipelines.finish(dispatch,"failed",graph[1],"controlled failure");
        var full=Arrays.stream(graph).boxed().toList();long retry=pipelines.create(graph[1],date,WorkflowActor.pipeline(user(44,9)),full,List.of(graph[1]),run,graph[1],120);
        var next=pipelines.begin(retry);var retried=pipelines.prepare(next,graph[1]);
        var manifest=FixedJson.parse(sql.lookup(retried.execution().instance().getId(),retried.execution().attemptId(),retried.execution().fencingToken()).canonicalManifest());
        assertEquals(44,manifest.at("/actor/uid").asLong());assertEquals(9,manifest.at("/actor/accessVersion").asLong());assertEquals(1,attempts(graph[0]));
        record(next,graph[1],retried,complete(retried,false));pipelines.finish(next,"failed",graph[1],"controlled retry failure");
        long again=pipelines.create(graph[1],date,actor(),full,List.of(graph[1]),retry,graph[1],120);assertNotNull(pipelines.prepare(pipelines.begin(again),graph[1]));
    }
    @Test void historicalUsernameRowsStayUntrustedAndDoNotResumeFromLatestSuccess(){
        long id=task();approve(id);long run=jdbc.queryForObject("INSERT INTO workflow.pipeline_run(target_task_id,biz_date,status,triggered_by) VALUES(?,?,'unknown','fixture-user-42') RETURNING id",Long.class,id,date);
        var manual=sql.claimManual(id,date,WorkflowActor.manual(user(42,3)));complete(manual,true);
        jdbc.update("INSERT INTO workflow.pipeline_node_run(pipeline_run_id,task_id,sequence_no,status,instance_id) VALUES(?,?,1,'unknown',?)",run,id,manual.execution().instance().getId());
        assertTrue(pipelines.recover().isEmpty());assertEquals("unknown",state(run));assertThrows(IllegalStateException.class,()->pipelines.begin(run));
        assertThrows(IllegalStateException.class,()->create(new long[]{id}));
    }
}
