package com.aetheris.ingestion.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.config.BusinessCalendar;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly invoked only against the disposable PostgreSQL acceptance database. */
class SchedulerOutboxPostgresIT {
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    @BeforeAll static void migrateDisposableDatabase() {
        String url=System.getenv("AETHERIS_TEST_POSTGRES_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit"))
            throw new IllegalStateException("Requires isolated loopback aetheris_audit PostgreSQL");
        source=new DriverManagerDataSource(url,"audit_admin",System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD"));
        jdbc=new JdbcTemplate(source); transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        assertEquals("aetheris_audit",jdbc.queryForObject("SELECT current_database()",String.class));
        Flyway.configure().dataSource(source).schemas("ingestion").defaultSchema("ingestion")
                .locations("classpath:db/migration").load().migrate();
    }
    static long insert() {
        return jdbc.queryForObject("INSERT INTO ingestion.ingestion_task(name,source_type,target_table,schedule_cron,status) VALUES('it','jdbc','warehouse.t','0 0 2 * * ?','enabled') RETURNING id",Long.class);
    }
    static long generation(long id) {
        return jdbc.queryForObject("SELECT schedule_version FROM ingestion.ingestion_task WHERE id=?",Long.class,id);
    }
    static long desiredGeneration(long id) {
        return jdbc.queryForObject("SELECT generation FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",Long.class,id);
    }
    @Test void commitsOutboxAtomicallyAndRuntimeUpdatesDoNotReschedule() {
        long id=insert(), initial=generation(id);
        assertEquals(initial,desiredGeneration(id));
        jdbc.update("UPDATE ingestion.scheduler_sync_outbox SET status='applied' WHERE resource_id=?",id);
        jdbc.update("UPDATE ingestion.ingestion_task SET last_run_status='passed',updated_at=now(),schedule_version=999999 WHERE id=?",id);
        assertEquals(initial,generation(id)); assertEquals(initial,desiredGeneration(id));
        assertEquals("applied",jdbc.queryForObject("SELECT status FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",String.class,id));
        AtomicLong rolledBackId=new AtomicLong();
        transactions.executeWithoutResult(status -> { rolledBackId.set(insert()); status.setRollbackOnly(); });
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ingestion.ingestion_task WHERE id=?",Integer.class,rolledBackId.get()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",Integer.class,rolledBackId.get()));
    }
    @Test void tombstoneSurvivesDeleteAndRecreatedIdUsesHigherGeneration() {
        long id=insert(), old=generation(id);
        jdbc.update("DELETE FROM ingestion.ingestion_task WHERE id=?",id);
        long tombstone=desiredGeneration(id); assertTrue(tombstone>old);
        assertTrue(jdbc.queryForObject("SELECT deleted FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",Boolean.class,id));
        jdbc.update("INSERT INTO ingestion.ingestion_task(id,name,source_type,target_table,schedule_cron,status) VALUES(?,'it','jdbc','warehouse.t','0 0 2 * * ?','enabled')",id);
        assertTrue(generation(id)>tombstone); assertEquals(generation(id),desiredGeneration(id));
        assertFalse(jdbc.queryForObject("SELECT deleted FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",Boolean.class,id));
    }
    @Test void concurrentDefinitionsAreSerializedAndNewestSnapshotWins() throws Exception {
        long id=insert();
        try(Connection first=source.getConnection(); var executor=Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            try(PreparedStatement statement=first.prepareStatement("UPDATE ingestion.ingestion_task SET schedule_cron='0 0 3 * * ?' WHERE id=?")) {
                statement.setLong(1,id); statement.executeUpdate();
            }
            CountDownLatch secondConnected=new CountDownLatch(1);
            Future<?> second=executor.submit(()->{
                try(Connection connection=source.getConnection()) {
                    connection.setAutoCommit(false); secondConnected.countDown();
                    try(PreparedStatement statement=connection.prepareStatement("UPDATE ingestion.ingestion_task SET schedule_cron='0 0 4 * * ?' WHERE id=?")) {
                        statement.setLong(1,id); statement.executeUpdate(); connection.commit();
                    }
                } catch(SQLException error) { throw new RuntimeException(error); }
            });
            assertTrue(secondConnected.await(5,TimeUnit.SECONDS)); first.commit(); second.get(10,TimeUnit.SECONDS);
        }
        assertEquals("0 0 4 * * ?",jdbc.queryForObject("SELECT cron FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",String.class,id));
        assertEquals(generation(id),desiredGeneration(id));
    }
    @Test void failedApplyRemainsPendingAndRestartReconcilesCurrentVersionExactlyOnce() throws Exception {
        long id=insert();
        var failing=new SchedulerSyncOutbox(jdbc,transactions,item->{throw new IllegalStateException("simulated Quartz outage");});
        assertThrows(IllegalStateException.class,()->failing.reconcile(id));
        assertEquals("pending",jdbc.queryForObject("SELECT status FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",Integer.class,id));
        Properties props=new Properties(); props.setProperty("org.quartz.scheduler.instanceName","outbox-it-ingestion-"+UUID.randomUUID());
        props.setProperty("org.quartz.threadPool.threadCount","1"); props.setProperty("org.quartz.jobStore.class","org.quartz.impl.jdbcjobstore.JobStoreTX");
        props.setProperty("org.quartz.jobStore.driverDelegateClass","org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
        props.setProperty("org.quartz.jobStore.tablePrefix","ingestion.QRTZ_");
        props.setProperty("org.quartz.jobStore.dataSource","acceptance");
        props.setProperty("org.quartz.dataSource.acceptance.provider","hikaricp");
        props.setProperty("org.quartz.dataSource.acceptance.driver","org.postgresql.Driver");
        props.setProperty("org.quartz.dataSource.acceptance.URL",System.getenv("AETHERIS_TEST_POSTGRES_URL"));
        props.setProperty("org.quartz.dataSource.acceptance.user","audit_admin");
        props.setProperty("org.quartz.dataSource.acceptance.password",System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD"));
        props.setProperty("org.quartz.dataSource.acceptance.maxConnections","3");
        Scheduler quartz=new StdSchedulerFactory(props).getScheduler();
        try {
            var scheduler=new IngestionSchedulerService(quartz,jdbc,new DataSourceTransactionManager(source),
                    new BusinessCalendar(Clock.systemUTC(),ZoneId.of("Asia/Shanghai")));
            IngestionTask item=new IngestionTask(); item.setId(id); scheduler.sync(item);
            JobKey key=JobKey.jobKey("task-"+id,"aetheris-ingestion-tasks");
            JobDetail installed=quartz.getJobDetail(key); assertNotNull(installed);
            assertEquals(generation(id),installed.getJobDataMap().getLong("scheduleVersion"));
            java.util.Date next=quartz.getTrigger(TriggerKey.triggerKey("trigger-"+id,"aetheris-ingestion-tasks")).getNextFireTime();
            scheduler.sync(item);
            assertEquals(next,quartz.getTrigger(TriggerKey.triggerKey("trigger-"+id,"aetheris-ingestion-tasks")).getNextFireTime());
            quartz.shutdown(true);
            quartz=new StdSchedulerFactory(props).getScheduler();
            assertNotNull(quartz.getJobDetail(key));
            scheduler=new IngestionSchedulerService(quartz,jdbc,new DataSourceTransactionManager(source),new BusinessCalendar());
            scheduler.restoreDesiredSchedules(); scheduler.retryPending();
            assertEquals(generation(id),quartz.getJobDetail(key).getJobDataMap().getLong("scheduleVersion"));
            jdbc.update("UPDATE ingestion.ingestion_task SET status='disabled' WHERE id=?",id);
            scheduler.sync(item); assertFalse(quartz.checkExists(key));
            assertEquals("applied",jdbc.queryForObject("SELECT status FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",String.class,id));
        } finally { quartz.shutdown(true); }
    }
    @Test void onlyAppliedOldTombstonesArePruned() {
        long expired=insert(),pending=insert();
        jdbc.update("DELETE FROM ingestion.ingestion_task WHERE id IN (?,?)",expired,pending);
        jdbc.update("UPDATE ingestion.scheduler_sync_outbox SET status='applied',applied_at=now()-interval '31 days' WHERE resource_id=?",expired);
        jdbc.update("UPDATE ingestion.scheduler_sync_outbox SET applied_at=now()-interval '31 days',next_attempt_at=now()+interval '1 day' WHERE resource_id=?",pending);
        new SchedulerSyncOutbox(jdbc,transactions,item->{}).retryPending();
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",Integer.class,expired));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ingestion.scheduler_sync_outbox WHERE resource_id=? AND status='pending'",Integer.class,pending));
    }
    @Test void competingRecoveryWorkersSkipLockedGeneration() throws Exception {
        long id=insert(); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        var first=new SchedulerSyncOutbox(jdbc,transactions,item->{
            calls.incrementAndGet(); entered.countDown();
            try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test deadline"); }
            catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
        });
        var second=new SchedulerSyncOutbox(jdbc,transactions,item->calls.incrementAndGet());
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<?> running=executor.submit(()->first.reconcile(id));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try { second.reconcile(id); assertEquals(1,calls.get()); }
            finally { release.countDown(); }
            running.get(10,TimeUnit.SECONDS);
        }
        assertEquals("applied",jdbc.queryForObject("SELECT status FROM ingestion.scheduler_sync_outbox WHERE resource_id=?",String.class,id));
    }
}