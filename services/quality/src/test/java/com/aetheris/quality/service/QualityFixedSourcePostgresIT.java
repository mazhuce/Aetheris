package com.aetheris.quality.service;

import com.aetheris.quality.fixedexecution.*;
import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.security.V2QualityFixture;
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
class QualityFixedSourcePostgresIT {
    @Configuration @EnableTransactionManagement static class Transactions { }
    @Test void realPostgresApprovalCronClaimCanonicalSealConcurrencyAndRecovery() throws Exception {
        String url=System.getenv("AETHERIS_TEST_QUALITY_FIXED_URL");assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit"));
        var source=new DriverManagerDataSource(url,"audit_admin",System.getenv("AETHERIS_TEST_QUALITY_FIXED_PASSWORD"));var jdbc=new JdbcTemplate(source);
        var flyway=Flyway.configure().dataSource(source).schemas("quality").defaultSchema("quality").locations("classpath:db/migration").load();flyway.migrate();assertTrue(flyway.validateWithResult().validationSuccessful);
        try(var context=new AnnotationConfigApplicationContext();var f=new V2QualityFixture()) {
            context.register(Transactions.class);context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
            context.registerBean("one",ExecutionRecoveryService.class,()->new ExecutionRecoveryService(jdbc));
            context.registerBean("two",ExecutionRecoveryService.class,()->new ExecutionRecoveryService(jdbc));context.refresh();
            var recovery=context.getBean("one",ExecutionRecoveryService.class);var other=context.getBean("two",ExecutionRecoveryService.class);
            var runs=mock(QualityCheckResultService.class);when(runs.getById(any())).thenAnswer(i->jdbc.queryForObject("SELECT * FROM quality.quality_check_result WHERE id=?",(r,index)->{
                var run=new QualityCheckResult();run.setId(r.getLong("id"));run.setRuleId(r.getLong("rule_id"));run.setStatus(r.getString("status"));run.setExecutionSnapshot(r.getString("execution_snapshot"));return run;},(Object)i.getArgument(0)));
            var store=new QualityExecutionStore(jdbc,context.getBean(PlatformTransactionManager.class),recovery,runs);
            var store2=new QualityExecutionStore(jdbc,context.getBean(PlatformTransactionManager.class),other,runs);
            var actor=new AtomicReference<QualityActor>();f.request(r->actor.set(QualityActor.manual(TrustedUser.require(r))));
            long task=task(jdbc,"fixed-one");var definition=store.definition(task);
            assertFalse(definition.approved());assertThrows(IllegalStateException.class,()->store.claimManual(task,actor.get(),"unapproved"));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM quality.quality_check_result WHERE rule_id=?",Integer.class,task));
            definition=store.approve(definition,actor.get());assertTrue(definition.approved());assertFalse(definition.cronBound());
            long staleGeneration=definition.scheduleGeneration();jdbc.update("UPDATE quality.quality_rule SET cron_expression='0 0 3 * * ?' WHERE id=?",task);
            assertThrows(IllegalStateException.class,()->store.bindCron(task,1,staleGeneration,actor.get()));
            var approved=store.definition(task);var bound=store.bindCron(task,1,approved.scheduleGeneration(),actor.get());assertTrue(bound.cronBound());
            assertTrue(bound.scheduleGeneration()>approved.scheduleGeneration());assertTrue(bound.bindingGeneration()>approved.bindingGeneration());
            var cron=store.claimScheduled(task,bound.scheduleGeneration(),"schedule:bound:1");var lookup=store.lookup(cron.result().getId(),1);
            var manifest=FixedJson.parse(lookup.canonicalManifest());assertEquals(14,manifest.size());assertTrue(manifest.get("parameters").isEmpty());assertTrue(manifest.get("parent").isNull());
            assertEquals(FixedJson.parse(bound.canonicalDefinition()),manifest.get("execution"));assertEquals(FixedJson.parse(bound.cronActor()),manifest.get("actor"));
            assertEquals(FixedJson.parse(bound.canonicalDefinition()),FixedJson.parse(cron.result().getExecutionSnapshot()));
            assertEquals(BodyHash.sha256(lookup.canonicalManifest().getBytes(StandardCharsets.UTF_8)),lookup.manifestSha256());
            assertTrue(lookup.leaseExpiresAt()>java.time.Instant.now().getEpochSecond());
            store.bindReceipt(cron.result().getId(),99);store.bindReceipt(cron.result().getId(),99);assertThrows(IllegalStateException.class,()->store.bindReceipt(cron.result().getId(),100));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE quality.quality_check_result SET execution_snapshot='{}' WHERE id=?",cron.result().getId()));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE quality.quality_check_result SET invocation_json=jsonb_set(invocation_json,'{username}','\"admin\"') WHERE id=?",cron.result().getId()));
            jdbc.update("UPDATE quality.quality_rule SET cron_expression='0 0 4 * * ?' WHERE id=?",task);
            var unbound=store.definition(task);assertFalse(unbound.cronBound());
            var rebound=store.bindCron(task,unbound.revision(),unbound.scheduleGeneration(),actor.get());
            assertEquals(bound.cronActor(),rebound.cronActor()); // exact same USER proof, UID/name/AV and requestId
            assertTrue(rebound.scheduleGeneration()>bound.scheduleGeneration());assertTrue(rebound.bindingGeneration()>bound.bindingGeneration());
            assertThrows(IllegalStateException.class,()->store.lookup(cron.result().getId(),1),"A re-bound cron actor must not revive an old running admission");
            assertEquals(bound.scheduleGeneration(),jdbc.queryForObject("SELECT source_schedule_generation FROM quality.quality_check_result WHERE id=?",Long.class,cron.result().getId()));
            assertEquals(bound.bindingGeneration(),jdbc.queryForObject("SELECT source_binding_generation FROM quality.quality_check_result WHERE id=?",Long.class,cron.result().getId()));
            assertFalse(other.finish(completion(cron.result(),"passed"),null));assertTrue(recovery.finish(completion(cron.result(),"unknown"),null));
            assertThrows(IllegalStateException.class,()->store.lookup(cron.result().getId(),1));assertThrows(IllegalStateException.class,()->store.claimManual(task,actor.get(),"blocked-by-unknown"));
            jdbc.update("UPDATE quality.quality_rule SET target_table='warehouse.changed' WHERE id=?",task);
            recovery.reconcile(cron.result().getId(),"failed",true,"Remote query inspected and proven stopped","alice",3L);
            assertEquals("warehouse.t",jdbc.queryForObject("SELECT target_table FROM quality.quality_quarantine_record WHERE result_id=?",String.class,cron.result().getId()));
            assertEquals("failed",jdbc.queryForObject("SELECT status FROM quality.quality_check_result WHERE id=?",String.class,cron.result().getId()));
            store.approve(store.definition(task),actor.get());
            var replay=store.claimManual(task,actor.get(),"explicit-replay");assertTrue(replay.result().getId()>cron.result().getId());
            var quarantine=new com.aetheris.quality.entity.QualityQuarantineRecord();quarantine.setResultId(replay.result().getId());quarantine.setRuleId(task);quarantine.setViolationCount(3L);quarantine.setThresholdValue(2L);quarantine.setReason("fixture");
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->recovery.finish(completion(replay.result(),"failed"),quarantine));
            assertEquals("running",jdbc.queryForObject("SELECT status FROM quality.quality_check_result WHERE id=?",String.class,replay.result().getId()));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM quality.quality_quarantine_record WHERE result_id=?",Integer.class,replay.result().getId()));
            quarantine.setTargetTable("warehouse.changed");assertTrue(recovery.finish(completion(replay.result(),"failed"),quarantine));assertTrue(store.claimManual(task,actor.get(),"explicit-replay").reused());
            long concurrent=task(jdbc,"fixed-concurrent");store.approve(store.definition(concurrent),actor.get());AtomicInteger success=new AtomicInteger(),conflict=new AtomicInteger();
            try(var workers=Executors.newFixedThreadPool(2)) {
                var a=workers.submit(()->claim(store,concurrent,actor.get(),"one",success,conflict));var b=workers.submit(()->claim(store2,concurrent,actor.get(),"two",success,conflict));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            }
            assertEquals(1,success.get());assertEquals(1,conflict.get());
            long live=jdbc.queryForObject("SELECT id FROM quality.quality_check_result WHERE rule_id=? AND status='running'",Long.class,concurrent);
            jdbc.update("UPDATE quality.quality_check_result SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",live);recovery.heartbeatAndExpire();
            assertEquals("unknown",jdbc.queryForObject("SELECT status FROM quality.quality_check_result WHERE id=?",String.class,live));assertThrows(IllegalStateException.class,()->store.lookup(live,1));
            var before=store.definition(task);jdbc.update("UPDATE quality.quality_rule SET description='display-only' WHERE id=?",task);var display=store.definition(task);
            assertEquals(before.revision(),display.revision());assertEquals(before.approval(),display.approval());assertEquals(before.cronActor(),display.cronActor());
            jdbc.update("UPDATE quality.quality_rule SET expression='amount >= 1' WHERE id=?",task);var changed=store.definition(task);assertEquals(before.revision()+1,changed.revision());assertFalse(changed.approved());assertFalse(changed.cronBound());
            assertThrows(IllegalStateException.class,()->store.approve(before,actor.get()));assertThrows(IllegalStateException.class,()->store.claimManual(task,actor.get(),"old-revision"));
            generationGuards(jdbc,store,recovery,actor.get());
            long rollback=task(jdbc,"fixed-rollback");store.approve(store.definition(rollback),actor.get());
            doThrow(new IllegalStateException("Isolated post-seal result-read failure")).when(runs).getById(any());
            assertThrows(IllegalStateException.class,()->store.claimManual(rollback,actor.get(),"rollback-key"));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM quality.quality_check_result WHERE rule_id=?",Integer.class,rollback));
            assertEquals(0,flyway.info().pending().length);
        }
    }

    static void generationGuards(JdbcTemplate jdbc,QualityExecutionStore store,ExecutionRecoveryService recovery,QualityActor actor)throws Exception {
        long guarded=task(jdbc,"generation-guard");store.approve(store.definition(guarded),actor);
        var current=store.definition(guarded);var bound=store.bindCron(guarded,current.revision(),current.scheduleGeneration(),actor);
        long raw=jdbc.queryForObject("INSERT INTO quality.quality_check_result(rule_id,status,triggered_by,execution_snapshot,threshold_value) VALUES(?,'running',?,?,2) RETURNING id",Long.class,guarded,actor.username(),bound.canonicalDefinition());recovery.claim(raw);
        assertThrows(org.springframework.dao.DataAccessException.class,()->rawSeal(jdbc,raw,bound,actor.forCron().json().toString(),null,null));
        assertThrows(org.springframework.dao.DataAccessException.class,()->rawSeal(jdbc,raw,bound,actor.forCron().json().toString(),bound.scheduleGeneration()+1,bound.bindingGeneration()));
        assertEquals(1,rawSeal(jdbc,raw,bound,actor.forCron().json().toString(),bound.scheduleGeneration(),bound.bindingGeneration()));assertNotNull(store.lookup(raw,1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE quality.quality_check_result SET source_binding_generation=source_binding_generation+1 WHERE id=?",raw));
        // Isolated schema-owner fixture: simulate a V9 sealed cron row preserved NULL by V10.
        jdbc.execute("ALTER TABLE quality.quality_check_result DISABLE TRIGGER fixed_execution_source_generation_guard");
        try{jdbc.update("UPDATE quality.quality_check_result SET source_schedule_generation=NULL,source_binding_generation=NULL WHERE id=?",raw);}
        finally{jdbc.execute("ALTER TABLE quality.quality_check_result ENABLE TRIGGER fixed_execution_source_generation_guard");}
        assertNull(jdbc.queryForObject("SELECT source_schedule_generation FROM quality.quality_check_result WHERE id=?",Long.class,raw));
        assertThrows(IllegalStateException.class,()->store.lookup(raw,1));var legacy=new QualityCheckResult();legacy.setId(raw);legacy.setRuleId(guarded);
        assertTrue(recovery.finish(completion(legacy,"unknown"),null));assertEquals("unknown",jdbc.queryForObject("SELECT status FROM quality.quality_check_result WHERE id=?",String.class,raw));
        assertThrows(IllegalStateException.class,()->store.claimManual(guarded,actor,"legacy-unknown-slot"));
        long manual=task(jdbc,"manual-generation-guard");var definition=store.approve(store.definition(manual),actor);
        long manualRun=jdbc.queryForObject("INSERT INTO quality.quality_check_result(rule_id,status,triggered_by,execution_snapshot,threshold_value) VALUES(?,'running',?,?,2) RETURNING id",Long.class,manual,actor.username(),definition.canonicalDefinition());recovery.claim(manualRun);
        assertThrows(org.springframework.dao.DataAccessException.class,()->rawSeal(jdbc,manualRun,definition,actor.json().toString(),definition.scheduleGeneration(),definition.bindingGeneration()));
        assertEquals(1,rawSeal(jdbc,manualRun,definition,actor.json().toString(),null,null));assertNotNull(store.lookup(manualRun,1));
        var manualEntity=new QualityCheckResult();manualEntity.setId(manualRun);manualEntity.setRuleId(manual);assertTrue(recovery.finish(completion(manualEntity,"passed"),null));
    }
    static int rawSeal(JdbcTemplate jdbc,long id,QualityExecutionStore.Definition definition,String actor,Long schedule,Long binding)throws Exception {
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("origin","quality");manifest.put("operation","metadata.fixed-quality-query.submit");
        manifest.put("resourceId",definition.id());manifest.put("runId",id);manifest.put("attemptId",0);manifest.put("fence",1);manifest.put("executionRevision",definition.revision());manifest.put("definitionSha256",definition.definitionSha256());
        manifest.put("actor",FixedJson.parse(actor));manifest.put("approval",FixedJson.parse(definition.approval()));manifest.put("execution",FixedJson.parse(definition.canonicalDefinition()));manifest.put("parameters",Map.of());manifest.put("parent",null);
        String json=FixedJson.JSON.writeValueAsString(manifest);
        return jdbc.update("UPDATE quality.quality_check_result SET execution_revision=?,invocation_json=?::jsonb,approval_json=?::jsonb,sealed_manifest=?::jsonb,manifest_sha256=encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex'),manifest_sealed_at=clock_timestamp(),source_schedule_generation=?,source_binding_generation=? WHERE id=?",
                definition.revision(),actor,definition.approval(),json,json,schedule,binding,id);
    }
    static long task(JdbcTemplate jdbc,String name){return jdbc.queryForObject("INSERT INTO quality.quality_rule(name,datasource_id,rule_type,target_table,expression,threshold,status,cron_expression) VALUES(?,10,'range','warehouse.t','amount >= 0',2,'enabled','0 0 2 * * ?') RETURNING id",Long.class,name);}
    static QualityCheckResult completion(QualityCheckResult original,String status){var r=new QualityCheckResult();r.setId(original.getId());r.setRuleId(original.getRuleId());r.setStatus(status);r.setMessage("Isolated fixed query outcome");r.setThresholdValue(2L);if("passed".equals(status)||"failed".equals(status)){r.setViolationCount("passed".equals(status)?1L:3L);r.setDurationMs(1L);}r.setFinishedAt(OffsetDateTime.now());return r;}
    static void claim(QualityExecutionStore store,long id,QualityActor actor,String key,AtomicInteger success,AtomicInteger conflict){try{store.claimManual(id,actor,key);success.incrementAndGet();}catch(IllegalStateException denied){conflict.incrementAndGet();}}
}
