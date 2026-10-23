package com.aetheris.quality.service;

import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.entity.QualityQuarantineRecord;
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
import static org.junit.jupiter.api.Assertions.*;

class ExecutionRecoveryPostgresIT {
    @Configuration @EnableTransactionManagement static class Transactions { }
    @Test void migratesLegacyResultFencesWorkersAndCommitsQuarantineWithResult() {
        String url=System.getenv("AETHERIS_TEST_POSTGRES_URL");
        assertNotNull(url); assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit"));
        var source=new DriverManagerDataSource(url,"audit_admin",System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("quality").defaultSchema("quality")
                .locations("classpath:db/migration").target("6").load().migrate();
        long rule=jdbc.queryForObject("INSERT INTO quality.quality_rule(name,rule_type,target_table,expression,threshold,status) VALUES('recovery-it','sql','warehouse.t','SELECT 1',3,'enabled') RETURNING id",Long.class);
        long legacy=insert(jdbc,rule);
        var flyway=Flyway.configure().dataSource(source).schemas("quality").defaultSchema("quality")
                .locations("classpath:db/migration").load();
        flyway.migrate(); assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals("unknown",status(jdbc,legacy));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->insert(jdbc,rule));
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
            context.registerBean("one",ExecutionRecoveryService.class,()->new ExecutionRecoveryService(jdbc));
            context.registerBean("two",ExecutionRecoveryService.class,()->new ExecutionRecoveryService(jdbc));
            context.refresh();
            var one=context.getBean("one",ExecutionRecoveryService.class);
            var two=context.getBean("two",ExecutionRecoveryService.class);
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(one));
            assertThrows(IllegalArgumentException.class,()->one.reconcile(legacy,"passed",true,"query and persisted result checked by operator","admin",4L));
            assertEquals("unknown",status(jdbc,legacy));
            one.reconcile(legacy,"error",true,"query and persisted result checked by operator","admin",null);

            long normal=insert(jdbc,rule); one.claim(normal);
            var result=completion(normal,rule);
            var quarantine=quarantine(normal,rule);
            assertFalse(two.finish(result,quarantine));
            quarantine.setTargetTable(null);
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->one.finish(result,quarantine));
            assertEquals("running",status(jdbc,normal));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM quality.quality_quarantine_record WHERE result_id=?",Integer.class,normal));
            quarantine.setTargetTable("warehouse.t");
            assertTrue(one.finish(result,quarantine)); assertFalse(one.finish(result,quarantine));
            assertEquals("failed",status(jdbc,normal));
            assertEquals("failed",jdbc.queryForObject("SELECT last_check_status FROM quality.quality_rule WHERE id=?",String.class,rule));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quality.quality_quarantine_record WHERE result_id=?",Integer.class,normal));

            long expired=insert(jdbc,rule); one.claim(expired);
            jdbc.update("UPDATE quality.quality_check_result SET lease_expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",expired);
            two.heartbeatAndExpire(); one.heartbeatAndExpire();
            assertEquals("unknown",status(jdbc,expired));
            assertEquals("unknown",jdbc.queryForObject("SELECT last_check_status FROM quality.quality_rule WHERE id=?",String.class,rule));
            assertEquals(1,one.pending(50).stream().filter(row->row.id()==expired).count());
            assertEquals("{}",one.detail(expired).executionSnapshot());
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM quality.quality_rule WHERE id=?",rule));
            assertFalse(one.finish(completion(expired,rule),quarantine(expired,rule)));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->insert(jdbc,rule));
            one.reconcile(expired,"failed",true,"query and persisted result checked by operator","admin",4L);
            assertThrows(IllegalStateException.class,()->two.reconcile(expired,"error",true,"query and persisted result checked by operator","admin",null));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quality.quality_quarantine_record WHERE result_id=?",Integer.class,expired));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quality.execution_reconciliation_audit WHERE run_id=? AND previous_status='unknown'",Integer.class,expired));
            assertTrue(insert(jdbc,rule)>expired); assertEquals(0,flyway.info().pending().length);
        }
    }
    private static long insert(JdbcTemplate jdbc,long rule) {
        return jdbc.queryForObject("INSERT INTO quality.quality_check_result(rule_id,status,threshold_value) VALUES(?,'running',3) RETURNING id",Long.class,rule);
    }
    private static String status(JdbcTemplate jdbc,long id) {
        return jdbc.queryForObject("SELECT status FROM quality.quality_check_result WHERE id=?",String.class,id);
    }
    private static QualityCheckResult completion(long id,long rule) {
        var result=new QualityCheckResult(); result.setId(id); result.setRuleId(rule); result.setStatus("failed");
        result.setThresholdValue(3L); result.setViolationCount(4L); result.setMessage("threshold exceeded");
        result.setFinishedAt(OffsetDateTime.now()); return result;
    }
    private static QualityQuarantineRecord quarantine(long id,long rule) {
        var record=new QualityQuarantineRecord(); record.setResultId(id); record.setRuleId(rule);
        record.setTargetTable("warehouse.t"); record.setViolationCount(4L); record.setThresholdValue(3L);
        record.setReason("threshold exceeded"); return record;
    }
}
