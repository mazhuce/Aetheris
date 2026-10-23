package com.aetheris.ingestion.service;

import com.aetheris.ingestion.fixedexecution.*;
import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.security.V2IngestionFixture;
import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.security.protocol.BodyHash;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit entry point for a new disposable loopback PG only, never an existing installation. */
class IngestionFixedSourcePostgresIT {
    @Configuration @EnableTransactionManagement static class Transactions { }
    @Test void realPostgresApprovalCronClaimCanonicalSealConcurrencyAndRecovery() throws Exception {
        String url=System.getenv("AETHERIS_TEST_INGESTION_FIXED_URL");assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit"));
        var source=new DriverManagerDataSource(url,"audit_admin",System.getenv("AETHERIS_TEST_INGESTION_FIXED_PASSWORD"));var jdbc=new JdbcTemplate(source);
        var flyway=Flyway.configure().dataSource(source).schemas("ingestion").defaultSchema("ingestion").locations("classpath:db/migration").load();flyway.migrate();assertTrue(flyway.validateWithResult().validationSuccessful);
        try(var context=new AnnotationConfigApplicationContext();var f=new V2IngestionFixture()) {
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
            context.registerBean("one",ExecutionRecoveryService.class,()->new ExecutionRecoveryService(jdbc));
            context.registerBean("two",ExecutionRecoveryService.class,()->new ExecutionRecoveryService(jdbc));context.refresh();
            var recovery=context.getBean("one",ExecutionRecoveryService.class);var other=context.getBean("two",ExecutionRecoveryService.class);
            var runs=mock(IngestionRunService.class);when(runs.getById(any())).thenAnswer(i->jdbc.queryForObject("SELECT * FROM ingestion.ingestion_run WHERE id=?",(r,index)->{
                var run=new IngestionRun();run.setId(r.getLong("id"));run.setTaskId(r.getLong("task_id"));run.setStatus(r.getString("status"));run.setExecutionSnapshot(r.getString("execution_snapshot"));run.setStartedAt(r.getObject("started_at",OffsetDateTime.class));return run;},(Object)i.getArgument(0)));
            var store=new IngestionExecutionStore(jdbc,context.getBean(PlatformTransactionManager.class),recovery,runs);
            var store2=new IngestionExecutionStore(jdbc,context.getBean(PlatformTransactionManager.class),other,runs);
            var actor=new AtomicReference<IngestionActor>();f.request(r->actor.set(IngestionActor.manual(TrustedUser.require(r))));
            long task=task(jdbc,"fixed-one");var definition=store.definition(task);
            assertFalse(definition.approved());assertThrows(IllegalStateException.class,()->store.claimManual(task,actor.get(),"unapproved",null));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ingestion.ingestion_run WHERE task_id=?",Integer.class,task));
            definition=store.approve(definition,actor.get());assertTrue(definition.approved());assertFalse(definition.cronBound());
            long staleGeneration=definition.scheduleGeneration();jdbc.update("UPDATE ingestion.ingestion_task SET schedule_cron='0 0 3 * * ?' WHERE id=?",task);
            assertThrows(IllegalStateException.class,()->store.bindCron(task,1,staleGeneration,actor.get()));
            var approved=store.definition(task);var bound=store.bindCron(task,1,approved.scheduleGeneration(),actor.get());assertTrue(bound.cronBound());
            assertTrue(bound.scheduleGeneration()>approved.scheduleGeneration());assertTrue(bound.bindingGeneration()>approved.bindingGeneration());
            var cron=store.claimScheduled(task,bound.scheduleGeneration(),"schedule:bound:1");var lookup=store.lookup(cron.run().getId(),1);
            var manifest=FixedJson.parse(lookup.canonicalManifest());assertEquals(14,manifest.size());assertTrue(manifest.get("parameters").isEmpty());assertTrue(manifest.get("parent").isNull());
            assertEquals(FixedJson.parse(bound.canonicalDefinition()),manifest.get("execution"));assertEquals(FixedJson.parse(bound.cronActor()),manifest.get("actor"));
            assertEquals(FixedJson.parse(bound.canonicalDefinition()),FixedJson.parse(cron.run().getExecutionSnapshot()));
            assertEquals(BodyHash.sha256(lookup.canonicalManifest().getBytes(StandardCharsets.UTF_8)),lookup.manifestSha256());
            assertTrue(lookup.leaseExpiresAt()>java.time.Instant.now().getEpochSecond());
            store.bindReceipt(cron.run().getId(),99);store.bindReceipt(cron.run().getId(),99);assertThrows(IllegalStateException.class,()->store.bindReceipt(cron.run().getId(),100));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ingestion.ingestion_run SET execution_snapshot='{}' WHERE id=?",cron.run().getId()));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ingestion.ingestion_run SET invocation_json=jsonb_set(invocation_json,'{username}','\"admin\"') WHERE id=?",cron.run().getId()));
            jdbc.update("UPDATE ingestion.ingestion_task SET schedule_cron='0 0 4 * * ?' WHERE id=?",task);
            var unbound=store.definition(task);assertFalse(unbound.cronBound());
            var rebound=store.bindCron(task,unbound.revision(),unbound.scheduleGeneration(),actor.get());
            assertEquals(bound.cronActor(),rebound.cronActor()); // exact same USER proof, UID/name/AV and requestId
            assertTrue(rebound.scheduleGeneration()>bound.scheduleGeneration());assertTrue(rebound.bindingGeneration()>bound.bindingGeneration());
            assertThrows(IllegalStateException.class,()->store.lookup(cron.run().getId(),1),"A re-bound cron actor must not revive an old running admission");
            assertEquals(bound.scheduleGeneration(),jdbc.queryForObject("SELECT source_schedule_generation FROM ingestion.ingestion_run WHERE id=?",Long.class,cron.run().getId()));
            assertEquals(bound.bindingGeneration(),jdbc.queryForObject("SELECT source_binding_generation FROM ingestion.ingestion_run WHERE id=?",Long.class,cron.run().getId()));
            assertFalse(other.finish(completion(cron.run(),"success")));assertTrue(recovery.finish(completion(cron.run(),"unknown")));
            assertThrows(IllegalStateException.class,()->store.lookup(cron.run().getId(),1));assertThrows(IllegalStateException.class,()->store.claimManual(task,actor.get(),"blocked-by-unknown",null));
            recovery.reconcile(cron.run().getId(),"failed",true,"Target transaction inspected and proven stopped","alice",null,null);
            var replay=store.claimManual(task,actor.get(),"explicit-replay",cron.run().getId());assertTrue(replay.run().getId()>cron.run().getId());
            assertTrue(recovery.finish(completion(replay.run(),"success")));assertTrue(store.claimManual(task,actor.get(),"explicit-replay",cron.run().getId()).reused());
            long concurrent=task(jdbc,"fixed-concurrent");store.approve(store.definition(concurrent),actor.get());AtomicInteger success=new AtomicInteger(),conflict=new AtomicInteger();
            try(var workers=Executors.newFixedThreadPool(2)) {
                var a=workers.submit(()->claim(store,concurrent,actor.get(),"one",success,conflict));var b=workers.submit(()->claim(store2,concurrent,actor.get(),"two",success,conflict));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            }
            assertEquals(1,success.get());assertEquals(1,conflict.get());
            long live=jdbc.queryForObject("SELECT id FROM ingestion.ingestion_run WHERE task_id=? AND status='running'",Long.class,concurrent);
            jdbc.update("UPDATE ingestion.ingestion_run SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",live);recovery.heartbeatAndExpire();
            assertEquals("unknown",jdbc.queryForObject("SELECT status FROM ingestion.ingestion_run WHERE id=?",String.class,live));assertThrows(IllegalStateException.class,()->store.lookup(live,1));
            var before=store.definition(task);jdbc.update("UPDATE ingestion.ingestion_task SET description='display-only' WHERE id=?",task);var display=store.definition(task);
            assertEquals(before.revision(),display.revision());assertEquals(before.approval(),display.approval());assertEquals(before.cronActor(),display.cronActor());
            jdbc.update("UPDATE ingestion.ingestion_task SET source_sql='SELECT 2 AS id' WHERE id=?",task);var changed=store.definition(task);assertEquals(before.revision()+1,changed.revision());assertFalse(changed.approved());assertFalse(changed.cronBound());
            assertThrows(IllegalStateException.class,()->store.approve(before,actor.get()));assertThrows(IllegalStateException.class,()->store.claimManual(task,actor.get(),"old-revision",cron.run().getId()));
            generationGuards(jdbc,store,recovery,actor.get());
            long rollback=task(jdbc,"fixed-rollback");store.approve(store.definition(rollback),actor.get());
            doThrow(new IllegalStateException("Isolated post-seal result-read failure")).when(runs).getById(any());
            assertThrows(IllegalStateException.class,()->store.claimManual(rollback,actor.get(),"rollback-key",null));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ingestion.ingestion_run WHERE task_id=?",Integer.class,rollback));
            assertEquals(0,flyway.info().pending().length);
        }
    }

    static void generationGuards(JdbcTemplate jdbc,IngestionExecutionStore store,ExecutionRecoveryService recovery,IngestionActor actor)throws Exception {
        long guarded=task(jdbc,"generation-guard");store.approve(store.definition(guarded),actor);
        var current=store.definition(guarded);var bound=store.bindCron(guarded,current.revision(),current.scheduleGeneration(),actor);
        long raw=jdbc.queryForObject("INSERT INTO ingestion.ingestion_run(task_id,status,triggered_by,execution_snapshot) VALUES(?,'running',?,?) RETURNING id",Long.class,guarded,actor.username(),bound.canonicalDefinition());recovery.claim(raw);
        assertThrows(org.springframework.dao.DataAccessException.class,()->rawSeal(jdbc,raw,bound,actor.forCron().json().toString(),null,null));
        assertThrows(org.springframework.dao.DataAccessException.class,()->rawSeal(jdbc,raw,bound,actor.forCron().json().toString(),bound.scheduleGeneration()+1,bound.bindingGeneration()));
        assertEquals(1,rawSeal(jdbc,raw,bound,actor.forCron().json().toString(),bound.scheduleGeneration(),bound.bindingGeneration()));assertNotNull(store.lookup(raw,1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ingestion.ingestion_run SET source_binding_generation=source_binding_generation+1 WHERE id=?",raw));
        // Isolated schema-owner fixture: simulate a V9 sealed cron row preserved NULL by V10.
        jdbc.execute("ALTER TABLE ingestion.ingestion_run DISABLE TRIGGER fixed_execution_source_generation_guard");
        try{jdbc.update("UPDATE ingestion.ingestion_run SET source_schedule_generation=NULL,source_binding_generation=NULL WHERE id=?",raw);}
        finally{jdbc.execute("ALTER TABLE ingestion.ingestion_run ENABLE TRIGGER fixed_execution_source_generation_guard");}
        assertNull(jdbc.queryForObject("SELECT source_schedule_generation FROM ingestion.ingestion_run WHERE id=?",Long.class,raw));
        assertThrows(IllegalStateException.class,()->store.lookup(raw,1));var legacy=new IngestionRun();legacy.setId(raw);legacy.setTaskId(guarded);
        assertTrue(recovery.finish(completion(legacy,"unknown")));assertEquals("unknown",jdbc.queryForObject("SELECT status FROM ingestion.ingestion_run WHERE id=?",String.class,raw));
        assertThrows(IllegalStateException.class,()->store.claimManual(guarded,actor,"legacy-unknown-slot",null));
        long manual=task(jdbc,"manual-generation-guard");var definition=store.approve(store.definition(manual),actor);
        long manualRun=jdbc.queryForObject("INSERT INTO ingestion.ingestion_run(task_id,status,triggered_by,execution_snapshot) VALUES(?,'running',?,?) RETURNING id",Long.class,manual,actor.username(),definition.canonicalDefinition());recovery.claim(manualRun);
        assertThrows(org.springframework.dao.DataAccessException.class,()->rawSeal(jdbc,manualRun,definition,actor.json().toString(),definition.scheduleGeneration(),definition.bindingGeneration()));
        assertEquals(1,rawSeal(jdbc,manualRun,definition,actor.json().toString(),null,null));assertNotNull(store.lookup(manualRun,1));
        var manualEntity=new IngestionRun();manualEntity.setId(manualRun);manualEntity.setTaskId(manual);assertTrue(recovery.finish(completion(manualEntity,"success")));
    }
    static int rawSeal(JdbcTemplate jdbc,long id,IngestionExecutionStore.Definition definition,String actor,Long schedule,Long binding)throws Exception {
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("origin","ingestion");manifest.put("operation","metadata.fixed-transfer.submit");
        manifest.put("resourceId",definition.id());manifest.put("runId",id);manifest.put("attemptId",0);manifest.put("fence",1);manifest.put("executionRevision",definition.revision());manifest.put("definitionSha256",definition.definitionSha256());
        manifest.put("actor",FixedJson.parse(actor));manifest.put("approval",FixedJson.parse(definition.approval()));manifest.put("execution",FixedJson.parse(definition.canonicalDefinition()));manifest.put("parameters",Map.of());manifest.put("parent",null);
        String json=FixedJson.JSON.writeValueAsString(manifest);
        return jdbc.update("UPDATE ingestion.ingestion_run SET execution_revision=?,invocation_json=?::jsonb,approval_json=?::jsonb,sealed_manifest=?::jsonb,manifest_sha256=encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex'),manifest_sealed_at=clock_timestamp(),source_schedule_generation=?,source_binding_generation=? WHERE id=?",
                definition.revision(),actor,definition.approval(),json,json,schedule,binding,id);
    }
    static long task(JdbcTemplate jdbc,String name){return jdbc.queryForObject("INSERT INTO ingestion.ingestion_task(name,source_type,source_datasource_id,target_datasource_id,source_sql,target_table,write_mode,batch_size,status,schedule_cron) VALUES(?,'jdbc',10,20,'SELECT 1 AS id','warehouse.t','append',500,'enabled','0 0 2 * * ?') RETURNING id",Long.class,name);}
    static IngestionRun completion(IngestionRun original,String status){var r=new IngestionRun();r.setId(original.getId());r.setTaskId(original.getTaskId());r.setStatus(status);if("unknown".equals(status))r.setErrorMessage("Isolated fixture intentionally marks the transfer outcome unknown");r.setRowsRead(1L);r.setRowsWritten(1L);r.setDurationMs(1L);r.setFinishedAt(OffsetDateTime.now());return r;}
    static void claim(IngestionExecutionStore store,long id,IngestionActor actor,String key,AtomicInteger success,AtomicInteger conflict){try{store.claimManual(id,actor,key,null);success.incrementAndGet();}catch(IllegalStateException denied){conflict.incrementAndGet();}}
}
