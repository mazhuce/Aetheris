package com.aetheris.workflow.service;

import com.aetheris.workflow.fixedexecution.*;
import com.aetheris.workflow.service.ExecutionAdapter.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL/runtime roles and HTTP peers. Auth/engine results are controlled, not live Spark/Flink. */
class WorkflowEngineAuthorityPostgresIT extends PipelineSqlAuthorityPostgresIT {
    WorkflowEngineMaterial materials;WorkflowEngineAuthority authority;WorkflowEngineAuthorizationClient auth;
    SparkBatchExecutionAdapter adapter;SparkResourceQueueService queue;WorkflowSparkExecutor executor;
    MockEnvironment engineEnvironment;
    final List<HttpServer> servers=new ArrayList<>();final String digest="a".repeat(64),handle="b".repeat(32);
    @AfterEach void closePeers(){servers.forEach(s->s.stop(0));}
    void engineSetup(String origin){
        var policy=new OutboundTargetPolicy();adapter=new SparkBatchExecutionAdapter(RestClient.builder(),origin,"s".repeat(32),true,Duration.ofSeconds(2),policy,"");
        var catalog=new ManagedSparkCatalogService(jdbc,new ObjectMapper(),adapter,mock(GitRepositoryService.class));
        var env=new MockEnvironment().withProperty("aetheris.spark-runner.enabled","true").withProperty("aetheris.spark-runner.token","s".repeat(32)).withProperty("aetheris.spark-runner.base-url",origin).withProperty("aetheris.flink-jobmanager.base-url",origin);engineEnvironment=env;
        materials=new WorkflowEngineMaterial(jdbc,manager,catalog,policy,env);sql.installEngineMaterial(materials);
        auth=mock(WorkflowEngineAuthorizationClient.class);when(auth.authorize(any(),anyString(),anyString())).thenReturn(true);
        authority=new WorkflowEngineAuthority(jdbc,manager,sql,materials,auth);
        queue=new SparkResourceQueueService(jdbc,manager,catalog,adapter);executor=new WorkflowSparkExecutor(jdbc,authority,queue,adapter,coordinator);
    }
    long spark(String origin){
        engineSetup(origin);String key=UUID.randomUUID().toString().replace("-","");
        long env=jdbc.queryForObject("INSERT INTO workflow.compute_environment(environment_key,name,adapter_type,spark_version,java_version,python_version,master,max_cores,max_memory_mb,max_timeout_seconds,max_concurrency) VALUES(?,?,'managed_local_submit','3.5.6','21','3.12','local[1]',2,2048,1800,2) RETURNING id",Long.class,key,key);
        long artifact=jdbc.queryForObject("INSERT INTO workflow.managed_artifact(name,version,artifact_key,sha256,status,created_by,approved_by,approved_at) VALUES(?,'1','fixture.py',?,'approved','fixture-user-41','fixture-user-43',clock_timestamp()) RETURNING id",Long.class,key,digest);
        long job=jdbc.queryForObject("INSERT INTO workflow.spark_job_definition(name,environment_id,artifact_id,output_uri,driver_cores,driver_memory_mb,timeout_seconds,status,definition_digest,owner) VALUES(?,?,?,'hdfs://localhost:9000/user/aetheris/managed/fixture',1,768,30,'published',?,'fixture-user-41') RETURNING id",Long.class,key,env,artifact,"c".repeat(64));
        jdbc.update("INSERT INTO workflow.compute_runner(environment_id,runner_key,name,base_url,max_cores,max_memory_mb,max_concurrency,created_by) VALUES(?,?,?,?,2,2048,2,'fixture-user-43')",env,key,key,origin);
        long id=jdbc.queryForObject("INSERT INTO workflow.task(name,task_type,job_definition_id,timeout_seconds) VALUES(?,'spark',?,30) RETURNING id",Long.class,key,job);
        sql.approveEngine(sql.definition(id),WorkflowActor.manual(user(43,4)));return id;
    }
    long flink(String origin){return flink(origin,"streaming");}
    long flink(String origin,String mode){engineSetup(origin);long id=jdbc.queryForObject("INSERT INTO workflow.task(name,task_type,task_sql,flink_endpoint,flink_mode,timeout_seconds) VALUES(?,'flink','INSERT INTO sink SELECT 1',?,?,30) RETURNING id",Long.class,"flink-"+UUID.randomUUID(),origin,mode);sql.approveEngine(sql.definition(id),WorkflowActor.manual(user(43,4)));return id;}
    WorkflowExecutionStore.Claim manual(long id){return sql.claimManual(id,date,WorkflowActor.manual(user(42,3)));}
    SparkResourceQueueService.Admission admit(WorkflowExecutionStore.Claim c){var n=authority.original(c).material();queue.enqueueFrozen(c.execution(),n.path("environmentId").asLong(),1,768);var admission=queue.tryAdmit(c.execution().attemptId());assertNotNull(admission);return admission;}
    String state(WorkflowExecutionStore.Claim c){return jdbc.queryForObject("SELECT status FROM workflow.execution_attempt WHERE id=?",String.class,c.execution().attemptId());}
    String allocation(WorkflowExecutionStore.Claim c){return jdbc.queryForObject("SELECT status FROM workflow.spark_resource_allocation WHERE attempt_id=?",String.class,c.execution().attemptId());}
    String origin(HttpServer s){return "http://127.0.0.1:"+s.getAddress().getPort();}
    HttpServer server(com.sun.net.httpserver.HttpHandler handler)throws Exception {var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.createContext("/",handler);s.start();servers.add(s);return s;}
    void response(com.sun.net.httpserver.HttpExchange x,int status,String body)throws java.io.IOException {byte[] raw=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().add("Content-Type","application/json");x.sendResponseHeaders(status,raw.length);x.getResponseBody().write(raw);x.close();}
    String job(String key,String status){return "{\"id\":\""+handle+"\",\"requestKey\":\""+key+"\",\"status\":\""+status+"\",\"artifactDigest\":\""+digest+"\",\"affectedRows\":7,\"result\":{\"rows\":7}}";}
    @Test void sparkManualPinsActualMaterialAndKeepsOnlyBusinessDateInTheInvocation(){long id=spark("http://127.0.0.1:19101");var c=manual(id);var s=authority.original(c);assertEquals("manual",s.manifest().at("/actor/kind").asText());assertEquals(42,s.manifest().at("/actor/uid").asLong());assertEquals(digest,s.material().path("artifactSha256").asText());assertEquals(1,s.manifest().get("parameters").size());assertEquals(date,s.manifest().at("/parameters/bizDate").asText());assertEquals("queued",state(c));assertTrue(c.execution().leaseExpiresAt().toEpochSecond()-System.currentTimeMillis()/1000>=385);}
    @Test void changingJobOrArtifactBeforeClaimInvalidatesTheApprovedMaterialWithoutAnAttempt(){long id=spark("http://127.0.0.1:19102");jdbc.update("UPDATE workflow.spark_job_definition SET default_parameters='{\"changed\":true}' WHERE id=(SELECT job_definition_id FROM workflow.task WHERE id=?)",id);assertThrows(IllegalStateException.class,()->manual(id));assertEquals(0,attempts(id));}
    @Test void disabledRunnerOrRevokedArtifactCannotCreateAnAttempt(){long id=spark("http://127.0.0.1:19103");jdbc.update("UPDATE workflow.compute_runner SET status='disabled' WHERE environment_id=(SELECT environment_id FROM workflow.spark_job_definition WHERE id=(SELECT job_definition_id FROM workflow.task WHERE id=?))",id);assertThrows(IllegalStateException.class,()->manual(id));assertEquals(0,attempts(id));}
    @Test void dispatchOwnershipIsOneShotAcrossTwoConcurrentWorkers()throws Exception {long id=spark("http://127.0.0.1:19104");var c=manual(id);var a=admit(c);try(var workers=Executors.newFixedThreadPool(2)){var first=workers.submit(()->authority.begin(c,a.runnerId(),a.baseUrl()));var second=workers.submit(()->authority.begin(c,a.runnerId(),a.baseUrl()));assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));}assertNotNull(authority.original(c).token());}
    @Test void deniedOriginalActorProducesZeroEngineConnections()throws Exception {var requests=new AtomicInteger();var peer=server(x->{requests.incrementAndGet();response(x,500,"{}");});long id=spark(origin(peer));var c=manual(id);when(auth.authorize(any(),anyString(),anyString())).thenReturn(false);assertEquals("failed",executor.execute(c).getStatus());assertEquals(0,requests.get());assertNull(authority.original(c).token());assertEquals("released",allocation(c));}
    @Test void cronAndPipelineKeepTheirOriginalKindsAndRejectAChangedGeneration(){long id=spark("http://127.0.0.1:19106");jdbc.update("UPDATE workflow.task SET cron_expression='0 0 1 * * ?' WHERE id=?",id);var d=sql.definition(id);sql.bindCron(id,d.revision(),d.scheduleGeneration(),WorkflowActor.manual(user(42,3)));var c=sql.claimScheduled(id,date,sql.definition(id).scheduleGeneration());var a=admit(c);assertEquals("cron",authority.original(c).manifest().at("/actor/kind").asText());jdbc.update("UPDATE workflow.task SET cron_expression='0 1 1 * * ?' WHERE id=?",id);assertThrows(IllegalStateException.class,()->authority.begin(c,a.runnerId(),a.baseUrl()));long other=spark("http://127.0.0.1:19107");long run=create(new long[]{other});var dispatch=pipelines.begin(run);var child=pipelines.prepare(dispatch,other);assertEquals("pipeline",authority.original(child).manifest().at("/actor/kind").asText());assertEquals(42,authority.original(child).manifest().at("/actor/uid").asLong());}
    @Test void wrongRunnerAndExpiredLeaseAreRejectedByTheDatabase(){long id=spark("http://127.0.0.1:19108");var c=manual(id);var a=admit(c);assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET engine_dispatch_token=?::uuid,engine_dispatch_started_at=clock_timestamp(),engine_runner_origin='http://127.0.0.1:19109' WHERE id=?",UUID.randomUUID(),c.execution().attemptId()));jdbc.update("UPDATE workflow.task_instance SET lease_expires_at=NULL WHERE id=?",c.execution().instance().getId());assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET engine_dispatch_token=?::uuid,engine_dispatch_started_at=clock_timestamp(),engine_runner_origin=? WHERE id=?",UUID.randomUUID(),a.baseUrl(),c.execution().attemptId()));}
    @Test void originalReceiptAndResultAreImmutableAndCannotCompleteANewerFence(){long id=spark("http://127.0.0.1:19110");var c=manual(id);var a=admit(c);assertTrue(authority.begin(c,a.runnerId(),a.baseUrl()));var s=authority.original(c);authority.submitted(c,new ManagedHandle(handle,s.requestKey(),"running",null,digest));assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET remote_handle=? WHERE id=?","d".repeat(32),c.execution().attemptId()));assertEquals("success",authority.complete(c,new RemoteExecution(handle,"succeeded",null,7L,null,null,Map.of(),null)).getStatus());assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET engine_result_json='{}',engine_result_sha256=encode(sha256('{}'::bytea),'hex') WHERE id=?",c.execution().attemptId()));var newer=manual(id);assertThrows(IllegalStateException.class,()->authority.complete(c,new RemoteExecution(handle,"succeeded",null,7L,null,null,Map.of(),null)));assertEquals("queued",state(newer));}
    @Test void lostSubmitResponseIsReadBackAtTheOriginalOriginAndNeverPostedAgain()throws Exception {
        var posts=new AtomicInteger();var reads=new AtomicInteger();var key=new AtomicReference<String>();
        var peer=server(x->{String path=x.getRequestURI().getPath();if(path.startsWith("/v1/artifacts/")){response(x,200,"{\"key\":\"fixture.py\",\"sha256\":\""+digest+"\",\"size\":1}");return;}if("POST".equals(x.getRequestMethod())){posts.incrementAndGet();key.set(FixedJson.parse(x.getRequestBody().readAllBytes(),65536).path("requestKey").asText());x.close();return;}reads.incrementAndGet();response(x,200,job(key.get(),path.contains("by-request")?"running":"succeeded"));});
        long id=spark(origin(peer));var c=manual(id);executor.execute(c);assertEquals(1,posts.get());assertNotNull(authority.original(c).receiptHash());
        var other=server(x->{fail("changed runner must never receive old attempt readback");});jdbc.update("UPDATE workflow.compute_runner SET base_url=? WHERE id=?",origin(other),authority.original(c).runnerId());
        when(auth.authorize(any(),anyString(),anyString())).thenReturn(false);assertEquals("success",executor.reconcile(c).getStatus());assertEquals("released",allocation(c));assertEquals(1,posts.get());assertTrue(reads.get()>=2);
    }
    @Test void unknownWithoutAReceiptRetainsCapacityAndTheDispatchMarker()throws Exception {var posts=new AtomicInteger();var peer=server(x->{if(x.getRequestURI().getPath().startsWith("/v1/artifacts/")){response(x,200,"{\"key\":\"fixture.py\",\"sha256\":\""+digest+"\",\"size\":1}");}else if("POST".equals(x.getRequestMethod())){posts.incrementAndGet();response(x,503,"{}");}else response(x,404,"{}");});long id=spark(origin(peer));var c=manual(id);assertEquals("unknown",executor.execute(c).getStatus());executor.reconcile(c);assertEquals(1,posts.get());assertEquals("admitted",allocation(c));assertNotNull(authority.original(c).token());assertThrows(IllegalStateException.class,()->manual(id));}
    @Test void flinkStagesAppendOnceAndNeverMutateTheOriginalReceipt(){long id=flink("http://127.0.0.1:19114");var c=manual(id);var s=authority.original(c);assertTrue(authority.begin(c,null,s.material().path("gatewayOrigin").asText()));authority.beforeStep(c,0,"session","");authority.afterStep(c,0,"session","1".repeat(32),null);assertThrows(IllegalStateException.class,()->authority.beforeStep(c,0,"session",""));authority.beforeStep(c,1,"insert","INSERT INTO sink SELECT 1");authority.afterStep(c,1,"insert","1".repeat(32),"2".repeat(32));assertNotNull(authority.original(c).receiptHash());assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET engine_steps_json='[]' WHERE id=?",c.execution().attemptId()));assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET remote_handle=? WHERE id=?","3".repeat(32)+"/"+"4".repeat(32),c.execution().attemptId()));}
    WorkflowFlinkExecutor flinkExecutor(){return new WorkflowFlinkExecutor(jdbc,authority,coordinator,new FlinkSqlExecutionClient(RestClient.builder(),Duration.ofSeconds(1),100,new OutboundTargetPolicy()),new FlinkJobManagerClient(RestClient.builder(),"http://127.0.0.1:19115",Duration.ofSeconds(1),Duration.ofSeconds(2),new OutboundTargetPolicy()));}
    @Test void flinkGatewayAcknowledgementDoesNotCompleteThePhysicalJobAndOriginalOriginsSurviveDefinitionChanges()throws Exception {
        var posts=new AtomicInteger();var jobState=new AtomicReference<>("RUNNING");String jobId="3".repeat(32);
        var peer=server(x->{String path=x.getRequestURI().getPath();if("POST".equals(x.getRequestMethod())){posts.incrementAndGet();response(x,200,path.equals("/v1/sessions")?"{\"sessionHandle\":\""+"1".repeat(32)+"\"}":"{\"operationHandle\":\""+"2".repeat(32)+"\"}");}else if(path.startsWith("/jobs/"))response(x,200,"{\"jid\":\""+jobId+"\",\"state\":\""+jobState.get()+"\"}");else response(x,200,"{\"jobID\":\""+jobId+"\",\"status\":\"FINISHED\"}");});
        long id=flink(origin(peer));var c=manual(id);var f=flinkExecutor();assertEquals("running",f.execute(c).getStatus());assertEquals(2,posts.get());assertEquals("running",f.reconcile(c).getStatus());assertNull(jdbc.queryForObject("SELECT engine_result_json::text FROM workflow.execution_attempt WHERE id=?",String.class,c.execution().attemptId()));
        jdbc.update("UPDATE workflow.task SET flink_endpoint='http://127.0.0.1:19116' WHERE id=?",id);jobState.set("FINISHED");assertEquals("failed",f.reconcile(c).getStatus());assertEquals(2,posts.get());assertEquals(jobId,jdbc.queryForObject("SELECT flink_job_id FROM workflow.execution_attempt WHERE id=?",String.class,c.execution().attemptId()));
        assertEquals("FLINK_UNEXPECTED_FINISH",jdbc.queryForObject("SELECT engine_result_json->'remote'->>'errorCode' FROM workflow.execution_attempt WHERE id=?",String.class,c.execution().attemptId()));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET flink_job_id=? WHERE id=?","4".repeat(32),c.execution().attemptId()));
    }
    @Test void flinkLostInsertResponsePreservesTheUncertainStepAndNeverCreatesAnotherSession()throws Exception {
        var posts=new AtomicInteger();var peer=server(x->{posts.incrementAndGet();if(x.getRequestURI().getPath().equals("/v1/sessions"))response(x,200,"{\"sessionHandle\":\""+"1".repeat(32)+"\"}");else x.close();});
        long id=flink(origin(peer));var c=manual(id);var f=flinkExecutor();assertEquals("unknown",f.execute(c).getStatus());assertEquals(2,posts.get());f.reconcile(c);assertEquals(2,posts.get());assertNull(authority.original(c).handle());assertEquals("before",jdbc.queryForObject("SELECT engine_steps_json->-1->>'phase' FROM workflow.execution_attempt WHERE id=?",String.class,c.execution().attemptId()));assertThrows(IllegalStateException.class,()->manual(id));
    }
    @Test void flinkCannotSubstituteAnotherStatementEvenWithAValidDispatchToken(){long id=flink("http://127.0.0.1:19117");var c=manual(id);authority.begin(c,null,"http://127.0.0.1:19117");assertThrows(IllegalArgumentException.class,()->authority.beforeStep(c,1,"insert","INSERT INTO forbidden SELECT 1"));assertThrows(IllegalArgumentException.class,()->authority.beforeStep(c,1,"setup","INSERT INTO sink SELECT 1"));assertEquals(0,jdbc.queryForObject("SELECT jsonb_array_length(engine_steps_json) FROM workflow.execution_attempt WHERE id=?",Integer.class,c.execution().attemptId()));}
    @Test void knownTerminalRowsAndStateCannotBeRewrittenAfterReceiptCompletion(){long id=spark("http://127.0.0.1:19118");var c=manual(id);var a=admit(c);authority.begin(c,a.runnerId(),a.baseUrl());var s=authority.original(c);authority.submitted(c,new ManagedHandle(handle,s.requestKey(),"running",null,digest));authority.complete(c,new RemoteExecution(handle,"succeeded",null,7L,null,null,Map.of(),null));assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET affected_rows=999 WHERE id=?",c.execution().attemptId()));assertThrows(DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET status='running' WHERE id=?",c.execution().attemptId()));}
    @Test void environmentBudgetIsSharedAcrossRunnersAndUnknownRetainsItsReservation(){long id=spark("http://127.0.0.1:19119");jdbc.update("UPDATE workflow.compute_environment SET max_concurrency=1 WHERE id=(SELECT environment_id FROM workflow.spark_job_definition WHERE id=(SELECT job_definition_id FROM workflow.task WHERE id=?))",id);sql.approveEngine(sql.definition(id),WorkflowActor.manual(user(43,4)));var first=manual(id);var a=admit(first);authority.begin(first,a.runnerId(),a.baseUrl());coordinator.submissionUnknown(first.execution(),"controlled lost response");var second=sql.claimManual(id,"2026-10-26",WorkflowActor.manual(user(42,3)));var n=authority.original(second).material();queue.enqueueFrozen(second.execution(),n.path("environmentId").asLong(),1,768);assertNull(queue.tryAdmit(second.execution().attemptId()));assertEquals("admitted",allocation(first));assertEquals("queued",allocation(second));}
    void useDefaultRunner(long id){
        // The owned DB seeds one globally unique default alias. Retain those rows at distinct inert fixture origins.
        jdbc.update("UPDATE workflow.compute_runner SET base_url='http://127.0.0.1:'||(20000+id)::text WHERE base_url='config://default'");
        jdbc.update("UPDATE workflow.compute_runner SET base_url='config://default' WHERE environment_id=(SELECT environment_id FROM workflow.spark_job_definition WHERE id=(SELECT job_definition_id FROM workflow.task WHERE id=?))",id);sql.approveEngine(sql.definition(id),WorkflowActor.manual(user(43,4)));
    }
    @Test void defaultRunnerAliasIsResolvedAtApprovalAndNeverDispatchedAsAMutableAlias()throws Exception {
        var posts=new AtomicInteger();var peer=server(x->{if(x.getRequestURI().getPath().startsWith("/v1/artifacts/"))response(x,200,"{\"key\":\"fixture.py\",\"sha256\":\""+digest+"\",\"size\":1}");else{posts.incrementAndGet();String key=FixedJson.parse(x.getRequestBody().readAllBytes(),65536).path("requestKey").asText();response(x,202,job(key,"running"));}});
        long id=spark(origin(peer));useDefaultRunner(id);var c=manual(id);assertEquals(origin(peer),authority.original(c).material().at("/runners/0/origin").asText());assertEquals("running",executor.execute(c).getStatus());assertEquals(origin(peer),authority.original(c).origin());assertEquals(1,posts.get());
    }
    @Test void changingDefaultConfigurationAfterApprovalCannotConnectOrSubmitToEitherOrigin()throws Exception {
        var requests=new AtomicInteger();var original=server(x->{requests.incrementAndGet();response(x,500,"{}");});var replacement=server(x->{requests.incrementAndGet();response(x,500,"{}");});
        long id=spark(origin(original));useDefaultRunner(id);var c=manual(id);var a=admit(c);assertEquals(origin(original),a.baseUrl());engineEnvironment.withProperty("aetheris.spark-runner.base-url",origin(replacement));assertEquals("failed",executor.dispatch(c,a).getStatus());assertEquals(0,requests.get());assertNull(authority.original(c).token());assertEquals("released",allocation(c));
    }
    @Test void sparkPipelineUsesItsOwnEngineResultToOpenTheSqlDownstream(){completedEngineOpensSqlDownstream("spark");}
    @Test void flinkPipelineUsesItsOwnEngineResultToOpenTheSqlDownstream(){completedEngineOpensSqlDownstream("flink");streamingFinishedCannotOpenSqlDownstream();}
    void completedEngineOpensSqlDownstream(String engine){
        String endpoint="http://127.0.0.1:"+(engine.equals("spark")?19125:19126);
        // A natural successful finish belongs to a bounded job, not an unbounded stream.
        long upstream=engine.equals("spark")?spark(endpoint):flink(endpoint,"bounded"),downstream=task();
        jdbc.update("INSERT INTO workflow.task_dependency(task_id,upstream_task_id) VALUES(?,?)",downstream,upstream);approve(downstream);
        long run=create(new long[]{upstream,downstream});var dispatch=pipelines.begin(run);var claim=pipelines.prepare(dispatch,upstream);
        long node=jdbc.queryForObject("SELECT id FROM workflow.pipeline_node_run WHERE pipeline_run_id=? AND task_id=?",Long.class,run,upstream);
        assertFalse(jdbc.queryForObject("SELECT workflow.pipeline_success_evidence(?)",Boolean.class,node));
        if(engine.equals("spark")){
            var admitted=admit(claim);assertTrue(authority.begin(claim,admitted.runnerId(),admitted.baseUrl()));
            authority.submitted(claim,new ManagedHandle(handle,authority.original(claim).requestKey(),"running",null,digest));
        }else{
            assertTrue(authority.begin(claim,null,endpoint));authority.beforeStep(claim,0,"session","");authority.afterStep(claim,0,"session","1".repeat(32),null);
            authority.beforeStep(claim,1,"insert","INSERT INTO sink SELECT 1");authority.afterStep(claim,1,"insert","1".repeat(32),"2".repeat(32));
        }
        assertFalse(pipelines.record(dispatch,upstream,claim,claim.execution().instance(),"succeeded",null));
        var completed=authority.complete(claim,new RemoteExecution(authority.original(claim).handle(),"succeeded",null,7L,null,null,Map.of("controlledResult",true),null));
        assertNull(jdbc.queryForObject("SELECT metadata_receipt_id FROM workflow.execution_attempt WHERE id=?",Long.class,claim.execution().attemptId()));
        record(dispatch,upstream,claim,completed);
        assertTrue(jdbc.queryForObject("SELECT workflow.pipeline_success_evidence(?)",Boolean.class,node),"A sealed successful "+engine+" result must open only its original pipeline edge");
        var sqlChild=pipelines.prepare(dispatch,downstream);record(dispatch,downstream,sqlChild,complete(sqlChild,true));
        pipelines.finish(dispatch,"succeeded",null,null);assertEquals("succeeded",state(run));
    }
    void streamingFinishedCannotOpenSqlDownstream(){
        String endpoint="http://127.0.0.1:19127",job="5".repeat(32);
        long upstream=flink(endpoint),downstream=task();
        jdbc.update("INSERT INTO workflow.task_dependency(task_id,upstream_task_id) VALUES(?,?)",downstream,upstream);approve(downstream);
        long run=create(new long[]{upstream,downstream});var dispatch=pipelines.begin(run);var claim=pipelines.prepare(dispatch,upstream);
        assertTrue(authority.begin(claim,null,endpoint));authority.beforeStep(claim,0,"session","");authority.afterStep(claim,0,"session","1".repeat(32),null);
        authority.beforeStep(claim,1,"insert","INSERT INTO sink SELECT 1");authority.afterStep(claim,1,"insert","1".repeat(32),"2".repeat(32));
        assertTrue(coordinator.bindDiscoveredFlinkJob(claim.execution().instance().getId(),claim.execution().attemptId(),authority.original(claim).handle(),job));
        var jobManager=mock(FlinkJobManagerClient.class);when(jobManager.statusAt(endpoint,job)).thenReturn(new FlinkJobManagerClient.JobStatus(job,"FINISHED","controlled streaming finish"));
        var flink=new WorkflowFlinkExecutor(jdbc,authority,coordinator,mock(FlinkSqlExecutionClient.class),jobManager);
        var result=flink.reconcile(claim);assertEquals("failed",result.getStatus());
        assertFalse(pipelines.record(dispatch,upstream,claim,result,"succeeded",null));record(dispatch,upstream,claim,result);
        long node=jdbc.queryForObject("SELECT id FROM workflow.pipeline_node_run WHERE pipeline_run_id=? AND task_id=?",Long.class,run,upstream);
        assertFalse(jdbc.queryForObject("SELECT workflow.pipeline_success_evidence(?)",Boolean.class,node));
        assertThrows(IllegalStateException.class,()->pipelines.prepare(dispatch,downstream));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM workflow.execution_attempt WHERE instance_id IN (SELECT id FROM workflow.task_instance WHERE task_id=?)",Long.class,downstream));
        pipelines.finish(dispatch,"failed",upstream,"Flink streaming job ended without approved stop evidence");
    }
}
