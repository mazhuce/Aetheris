package com.aetheris.workflow.service;

import com.aetheris.workflow.fixedexecution.*;
import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.security.V2WorkflowFixture;
import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.security.protocol.BodyHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit entry point: fresh owned loopback tmpfs PostgreSQL only, never an installation. */
class WorkflowFixedSourcePostgresIT {
    @Test void runtimeApprovalGenerationAtomicClaimSealFencesAndUnknown() throws Exception {
        String url=System.getenv("AETHERIS_TEST_WORKFLOW_FIXED_URL");assertNotNull(url);assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit"));
        String password=Objects.requireNonNull(System.getenv("AETHERIS_TEST_WORKFLOW_FIXED_PASSWORD"));
        var owner=new DriverManagerDataSource(url,"audit_admin",password);var admin=new JdbcTemplate(owner);
        var flyway=Flyway.configure().dataSource(owner).schemas("workflow").defaultSchema("workflow").locations("classpath:db/migration").load();flyway.migrate();assertTrue(flyway.validateWithResult().validationSuccessful);assertEquals(0,flyway.info().pending().length);
        // Test-only credentials and grants; all objects remain migration-owned.
        admin.execute("CREATE ROLE audit_workflow_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD 'isolated-runtime-only'");
        admin.execute("REVOKE ALL ON SCHEMA workflow FROM PUBLIC");admin.execute("GRANT USAGE ON SCHEMA workflow TO audit_workflow_runtime");
        for(String table:admin.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='workflow' AND tablename<>'flyway_schema_history'",String.class)){
            assertTrue(table.matches("[a-z_][a-z0-9_]*"));admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON TABLE workflow."+table+" TO audit_workflow_runtime");
        }
        admin.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA workflow TO audit_workflow_runtime");
        admin.execute("REVOKE ALL ON ALL FUNCTIONS IN SCHEMA workflow FROM PUBLIC,audit_workflow_runtime");
        for(String signature:List.of("execution_identity_valid(jsonb)","execution_actor_valid(jsonb)","execution_approval_valid(jsonb,bigint,text)","execution_definition_projection(jsonb)"))admin.execute("GRANT EXECUTE ON FUNCTION workflow."+signature+" TO audit_workflow_runtime");
        admin.execute("CREATE SCHEMA other_service");admin.execute("CREATE TABLE other_service.private_data(value int)");
        var runtime=new DriverManagerDataSource(url,"audit_workflow_runtime","isolated-runtime-only");var jdbc=new JdbcTemplate(runtime);
        assertEquals("audit_workflow_runtime",jdbc.queryForObject("SELECT current_user",String.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForList("SELECT * FROM workflow.flyway_schema_history"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("CREATE TABLE public.forbidden(value int)"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForList("SELECT * FROM other_service.private_data"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForList("SELECT * FROM pg_authid"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("ALTER TABLE workflow.task ADD COLUMN forbidden int"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("SET ROLE audit_admin"));
        var manager=new DataSourceTransactionManager(runtime);var coordinator=new ExecutionClaimCoordinator(jdbc,manager,new ObjectMapper(),"workflow-fixed-test",60,8);
        var tasks=mock(TaskService.class);when(tasks.getById(any())).thenAnswer(i->jdbc.queryForObject("SELECT * FROM workflow.task WHERE id=?",new BeanPropertyRowMapper<>(Task.class),(Object)i.getArgument(0)));
        var store=new WorkflowExecutionStore(jdbc,manager,coordinator,tasks);var store2=new WorkflowExecutionStore(jdbc,manager,new ExecutionClaimCoordinator(jdbc,manager,new ObjectMapper(),"other-fixed-worker",60,8),tasks);
        try(var f=new V2WorkflowFixture()){
            var ref=new AtomicReference<WorkflowActor>();f.request(r->ref.set(WorkflowActor.manual(TrustedUser.require(r))));var actor=ref.get();
            sqlLeaseAndLateFailure(jdbc,manager,tasks,actor);
            long id=task(jdbc,"fixed-main");var d=store.definition(id);assertFalse(d.approved());
            assertThrows(IllegalStateException.class,()->store.claimManual(id,"2026-10-18",actor));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM workflow.task_instance WHERE task_id=?",Integer.class,id));
            var approved=store.approve(d,actor);assertTrue(approved.approved());assertFalse(approved.cronBound());
            long before=approved.scheduleGeneration();jdbc.update("UPDATE workflow.task SET cron_expression='0 0 3 * * ?' WHERE id=?",id);
            assertThrows(IllegalStateException.class,()->store.bindCron(id,approved.revision(),before,actor));
            var changed=store.definition(id);var bound=store.bindCron(id,changed.revision(),changed.scheduleGeneration(),actor);assertTrue(bound.cronBound());assertTrue(bound.scheduleGeneration()>changed.scheduleGeneration());
            assertThrows(IllegalStateException.class,()->store.claimScheduled(id,"2026-10-18",changed.scheduleGeneration()));
            var claim=store.claimScheduled(id,"2026-10-18",bound.scheduleGeneration());var e=claim.execution();assertTrue(claim.dependenciesReady());
            var lookup=store.lookup(e.instance().getId(),e.attemptId(),e.fencingToken());var manifest=FixedJson.parse(lookup.canonicalManifest());
            assertEquals(14,manifest.size());assertEquals(FixedJson.parse(bound.canonicalDefinition()),manifest.get("execution"));assertEquals("2026-10-18",manifest.path("parameters").path("bizDate").asText());assertTrue(manifest.get("parent").isNull());assertEquals("cron",manifest.path("actor").path("kind").asText());
            assertEquals(BodyHash.sha256(lookup.canonicalManifest().getBytes(StandardCharsets.UTF_8)),lookup.manifestSha256());assertTrue(lookup.leaseExpiresAt()>java.time.Instant.now().getEpochSecond());
            store.bindReceipt(claim,99);store.bindReceipt(claim,99);assertThrows(IllegalStateException.class,()->store.bindReceipt(claim,100));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET task_sql='INSERT INTO x SELECT 2' WHERE id=?",e.attemptId()));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET invocation_json=jsonb_set(invocation_json,'{kind}','\"pipeline\"') WHERE id=?",e.attemptId()));
            assertThrows(IllegalStateException.class,()->store.lookup(e.instance().getId(),e.attemptId(),e.fencingToken()+1));
            assertThrows(IllegalStateException.class,()->store.claimManual(id,"2026-10-18",actor));
            jdbc.update("UPDATE workflow.task SET cron_expression='0 0 4 * * ?' WHERE id=?",id);
            var unbound=store.definition(id);assertFalse(unbound.cronBound());
            var rebound=store.bindCron(id,unbound.revision(),unbound.scheduleGeneration(),actor);
            assertEquals(bound.cronActor(),rebound.cronActor());assertTrue(rebound.scheduleGeneration()>bound.scheduleGeneration());assertTrue(rebound.bindingGeneration()>bound.bindingGeneration());
            assertThrows(IllegalStateException.class,()->store.lookup(e.instance().getId(),e.attemptId(),e.fencingToken()));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE workflow.execution_attempt SET source_schedule_generation=? WHERE id=?",rebound.scheduleGeneration(),e.attemptId()));
            coordinator.submissionUnknown(e,"fixture ambiguity");assertThrows(IllegalStateException.class,()->store.claimManual(id,"2026-10-18",actor));assertThrows(IllegalStateException.class,()->store.lookup(e.instance().getId(),e.attemptId(),e.fencingToken()));
            var next=store.claimManual(id,"2026-10-19",actor);assertEquals("manual",FixedJson.parse(store.lookup(next.execution().instance().getId(),next.execution().attemptId(),next.execution().fencingToken()).canonicalManifest()).path("actor").path("kind").asText());
            store.bindReceipt(next,101);coordinator.completeFixedSql(next.execution(),new SqlExecutionClient.ExecutionResult(null,1));var rerun=store.claimManual(id,"2026-10-19",actor);assertTrue(rerun.execution().attemptId()>next.execution().attemptId());assertTrue(rerun.execution().fencingToken()>next.execution().fencingToken());
            assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->coordinator.completeFixedSql(next.execution(),new SqlExecutionClient.ExecutionResult(1L,1)));
            jdbc.update("UPDATE workflow.execution_attempt SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",rerun.execution().attemptId());
            assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->coordinator.completeFixedSql(rerun.execution(),new SqlExecutionClient.ExecutionResult(1L,1)));
            coordinator.submissionUnknown(rerun.execution(),"expired source fixture");
            assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->coordinator.completeFixedSql(rerun.execution(),new SqlExecutionClient.ExecutionResult(1L,1)));
            var display=store.definition(id);jdbc.update("UPDATE workflow.task SET description='display' WHERE id=?",id);assertEquals(display,store.definition(id));
            jdbc.update("UPDATE workflow.task SET task_sql='INSERT INTO warehouse.target SELECT 2' WHERE id=?",id);var revised=store.definition(id);assertTrue(revised.revision()>display.revision());assertFalse(revised.approved());assertFalse(revised.cronBound());assertThrows(IllegalStateException.class,()->store.approve(display,actor));assertThrows(IllegalStateException.class,()->store.lookup(rerun.execution().instance().getId(),rerun.execution().attemptId(),rerun.execution().fencingToken()));
            long upstream=task(jdbc,"fixed-upstream");store.approve(revised,actor);long oldRevision=store.definition(id).revision();
            jdbc.update("INSERT INTO workflow.task_dependency(task_id,upstream_task_id) VALUES(?,?)",id,upstream);var withEdge=store.definition(id);assertEquals(oldRevision+1,withEdge.revision());assertFalse(withEdge.approved());assertEquals(upstream,FixedJson.parse(withEdge.canonicalDefinition()).path("dependencies").path("upstreamTaskIds").get(0).asLong());
            store.approve(withEdge,actor);var blocked=store.claimManual(id,"2026-10-20",actor);assertFalse(blocked.dependenciesReady());coordinator.skip(blocked.execution(),"dependency not ready");
            jdbc.update("DELETE FROM workflow.task_dependency WHERE task_id=?",id);assertFalse(store.definition(id).approved());assertTrue(FixedJson.parse(store.definition(id).canonicalDefinition()).path("dependencies").path("upstreamTaskIds").isEmpty());
            long parallel=task(jdbc,"fixed-concurrent");store.approve(store.definition(parallel),actor);AtomicInteger winners=new AtomicInteger(),losers=new AtomicInteger();
            try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->claim(store,parallel,actor,winners,losers));var b=pool.submit(()->claim(store2,parallel,actor,winners,losers));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}
            assertEquals(1,winners.get());assertEquals(1,losers.get());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.id=a.instance_id WHERE i.task_id=?",Integer.class,parallel));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM workflow.scheduler_sync_outbox o JOIN workflow.task t ON t.id=o.task_id WHERE o.status IN ('pending','processing') AND o.target_generation<t.schedule_version",Integer.class));
        }
    }
    static long task(JdbcTemplate jdbc,String name){return jdbc.queryForObject("INSERT INTO workflow.task(name,datasource_id,task_sql,cron_expression) VALUES(?,10,'INSERT INTO warehouse.target SELECT 1','0 0 2 * * ?') RETURNING id",Long.class,name);}
    static void sqlLeaseAndLateFailure(JdbcTemplate jdbc,DataSourceTransactionManager manager,TaskService tasks,WorkflowActor actor) throws Exception {
        // A non-SQL global grace must not silently extend the sealed SQL contract.
        var coordinator=new ExecutionClaimCoordinator(jdbc,manager,new ObjectMapper(),"sql-deadline-worker",600,8);
        var store=new WorkflowExecutionStore(jdbc,manager,coordinator,tasks);
        for(Integer timeout:new Integer[]{1800,1,86400}){
            long id=task(jdbc,"sql-deadline-"+timeout);jdbc.update("UPDATE workflow.task SET timeout_seconds=? WHERE id=?",timeout,id);
            store.approve(store.definition(id),actor);var claim=store.claimManual(id,"2026-10-18",actor);var e=claim.execution();
            double remaining=jdbc.queryForObject("SELECT extract(epoch FROM lease_expires_at-clock_timestamp())::double precision FROM workflow.execution_attempt WHERE id=?",Double.class,e.attemptId());
            int effective=timeout==null?1800:timeout;
            assertTrue(remaining>effective+58&&remaining<=effective+60,"SQL source lease must use database time plus sealed timeout plus exactly 60 seconds");
            assertEquals(e.leaseExpiresAt(),jdbc.queryForObject("SELECT lease_expires_at FROM workflow.execution_attempt WHERE id=?",java.time.OffsetDateTime.class,e.attemptId()));
            // Neither success nor failure can finish a sealed source without the one bound receipt.
            assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->coordinator.completeFixedSql(e,new SqlExecutionClient.ExecutionResult(1L,1)));
            assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->coordinator.failFixedSql(e,"receipt not bound"));
            store.bindReceipt(claim,500+effective);
            var other=new ExecutionClaimCoordinator(jdbc,manager,new ObjectMapper(),"different-sql-worker",600,8);
            assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->other.failFixedSql(e,"wrong worker"));
            assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->other.completeFixedSql(e,new SqlExecutionClient.ExecutionResult(1L,1)));
            if(timeout==1){
                // A real short PostgreSQL lease expiry, without waiting for a 24-hour execution.
                jdbc.update("UPDATE workflow.execution_attempt SET lease_expires_at=clock_timestamp()+interval '100 milliseconds' WHERE id=?",e.attemptId());
                jdbc.update("UPDATE workflow.task_instance SET lease_expires_at=clock_timestamp()+interval '100 milliseconds' WHERE id=?",e.instance().getId());
                jdbc.execute("SELECT pg_sleep(0.2)");
                assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->coordinator.failFixedSql(e,"late failed receipt"));
                assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->coordinator.completeFixedSql(e,new SqlExecutionClient.ExecutionResult(1L,1)));
                assertEquals("running",jdbc.queryForObject("SELECT status FROM workflow.execution_attempt WHERE id=?",String.class,e.attemptId()));
                assertTrue(coordinator.reconcileExpiredLeases()>=1);
            }else coordinator.submissionUnknown(e,"signed result not reliably known");
            assertEquals("unknown",jdbc.queryForObject("SELECT status FROM workflow.execution_attempt WHERE id=?",String.class,e.attemptId()));
            assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->coordinator.failFixedSql(e,"late signed failed receipt"));
            assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,()->coordinator.completeFixedSql(e,new SqlExecutionClient.ExecutionResult(1L,1)));
            assertThrows(IllegalStateException.class,()->store.claimManual(id,"2026-10-18",actor));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.id=a.instance_id WHERE i.task_id=?",Integer.class,id));
        }
        long safe=task(jdbc,"sql-known-failure");store.approve(store.definition(safe),actor);var known=store.claimManual(safe,"2026-10-18",actor);store.bindReceipt(known,999);
        assertEquals("failed",coordinator.failFixedSql(known.execution(),"known failure receipt").getStatus());
    }
    static void claim(WorkflowExecutionStore store,long id,WorkflowActor actor,AtomicInteger winners,AtomicInteger losers){try{store.claimManual(id,"2026-10-18",actor);winners.incrementAndGet();}catch(IllegalStateException rejected){losers.incrementAndGet();}}
}
