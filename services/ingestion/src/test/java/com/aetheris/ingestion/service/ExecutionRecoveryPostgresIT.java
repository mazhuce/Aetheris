package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionRun;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.time.OffsetDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Invoked explicitly by the disposable-database acceptance script, never against an existing installation. */
class ExecutionRecoveryPostgresIT {
    @Configuration @EnableTransactionManagement static class Transactions { }

    @Test void migratesLegacyRunFencesExpiredWorkersAndSerializesManualReconciliation() throws Exception {
        String url=System.getenv("AETHERIS_TEST_POSTGRES_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit"));
        var source=new DriverManagerDataSource(url,"audit_admin",System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("ingestion").defaultSchema("ingestion")
                .locations("classpath:db/migration").target("6").load().migrate();
        long task=jdbc.queryForObject("INSERT INTO ingestion.ingestion_task(name,source_type,target_table,status) VALUES('recovery-it','jdbc','warehouse.t','enabled') RETURNING id",Long.class);
        long legacy=insert(jdbc,task);
        var flyway=Flyway.configure().dataSource(source).schemas("ingestion").defaultSchema("ingestion")
                .locations("classpath:db/migration").load();
        flyway.migrate(); assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals("unknown",status(jdbc,legacy));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->insert(jdbc,task));

        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
            context.registerBean("one",ExecutionRecoveryService.class,()->new ExecutionRecoveryService(jdbc));
            context.registerBean("two",ExecutionRecoveryService.class,()->new ExecutionRecoveryService(jdbc));
            context.refresh();
            var one=context.getBean("one",ExecutionRecoveryService.class);
            var two=context.getBean("two",ExecutionRecoveryService.class);
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(one));
            assertThrows(IllegalArgumentException.class,()->one.reconcile(legacy,"failed",false,"remote transaction inspected and stopped","admin",null,null));
            assertEquals("unknown",status(jdbc,legacy));
            one.reconcile(legacy,"failed",true,"remote transaction inspected and stopped","admin",null,null);

            long normal=insert(jdbc,task); one.claim(normal);
            var success=completion(normal,task);
            assertFalse(two.finish(success));
            assertEquals("running",status(jdbc,normal));
            assertTrue(one.finish(success)); assertFalse(one.finish(success));
            assertEquals("success",status(jdbc,normal));
            assertEquals("success",jdbc.queryForObject("SELECT last_run_status FROM ingestion.ingestion_task WHERE id=?",String.class,task));

            long expired=insert(jdbc,task); one.claim(expired);
            jdbc.update("UPDATE ingestion.ingestion_run SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",expired);
            one.heartbeatAndExpire(); two.heartbeatAndExpire();
            assertEquals("unknown",status(jdbc,expired));
            assertEquals("unknown",jdbc.queryForObject("SELECT last_run_status FROM ingestion.ingestion_task WHERE id=?",String.class,task));
            assertEquals(1,one.pending(50).stream().filter(row->row.id()==expired).count());
            assertEquals("{}",one.detail(expired).executionSnapshot());
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM ingestion.ingestion_task WHERE id=?",task));
            assertFalse(one.finish(completion(expired,task)));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->insert(jdbc,task));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ingestion.execution_reconciliation_audit WHERE run_id=? AND resolved_status='unknown'",Integer.class,expired));
            AtomicInteger successes=new AtomicInteger(),conflicts=new AtomicInteger();
            try(var workers=Executors.newFixedThreadPool(2)) {
                var a=workers.submit(()->resolve(one,expired,successes,conflicts));
                var b=workers.submit(()->resolve(two,expired,successes,conflicts));
                a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
            }
            assertEquals(1,successes.get()); assertEquals(1,conflicts.get());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ingestion.execution_reconciliation_audit WHERE run_id=? AND previous_status='unknown'",Integer.class,expired));
            assertEquals("failed",status(jdbc,expired));
            assertTrue(insert(jdbc,task)>expired);
            assertEquals(0,flyway.info().pending().length);
        }
    }
    private static void resolve(ExecutionRecoveryService service,long id,AtomicInteger successes,AtomicInteger conflicts) {
        try { service.reconcile(id,"failed",true,"remote transaction inspected and stopped","admin",null,null); successes.incrementAndGet(); }
        catch(IllegalStateException conflict) { conflicts.incrementAndGet(); }
    }
    private static long insert(JdbcTemplate jdbc,long task) {
        return jdbc.queryForObject("INSERT INTO ingestion.ingestion_run(task_id,status) VALUES(?,'running') RETURNING id",Long.class,task);
    }
    private static String status(JdbcTemplate jdbc,long id) {
        return jdbc.queryForObject("SELECT status FROM ingestion.ingestion_run WHERE id=?",String.class,id);
    }
    private static IngestionRun completion(long id,long task) {
        var run=new IngestionRun(); run.setId(id); run.setTaskId(task); run.setStatus("success");
        run.setRowsRead(1L); run.setRowsWritten(1L); run.setFinishedAt(OffsetDateTime.now()); return run;
    }
}
