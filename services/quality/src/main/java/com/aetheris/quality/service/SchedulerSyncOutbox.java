package com.aetheris.quality.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.function.Consumer;

/** Desired states are written by database triggers in the definition's own transaction. */
public final class SchedulerSyncOutbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Consumer<DesiredSchedule> apply;
    public SchedulerSyncOutbox(JdbcTemplate jdbc, TransactionTemplate transactions, Consumer<DesiredSchedule> apply) {
        this.jdbc=jdbc; this.transactions=transactions; this.apply=apply;
    }
    public void reconcile(long id) {
        RuntimeException error = transactions.execute(status -> {
            List<DesiredSchedule> desired = jdbc.query("""
                    SELECT resource_id,generation,deleted,name,cron,enabled FROM quality.scheduler_sync_outbox
                    WHERE resource_id=? FOR UPDATE SKIP LOCKED
                    """, (rs,row) -> new DesiredSchedule(rs.getLong("resource_id"),rs.getLong("generation"),
                    rs.getBoolean("deleted"),rs.getString("name"),rs.getString("cron"),rs.getBoolean("enabled")), id);
            if (desired.isEmpty()) return null;
            DesiredSchedule item=desired.getFirst();
            try {
                apply.accept(item);
                jdbc.update("""
                        UPDATE quality.scheduler_sync_outbox SET status='applied',last_error=NULL,
                            applied_at=now(),updated_at=now() WHERE resource_id=? AND generation=?
                        """,id,item.generation());
                return null;
            } catch (RuntimeException failure) {
                String message=failure.getMessage()==null ? "Quartz synchronization failed" : failure.getMessage();
                jdbc.update("""
                        UPDATE quality.scheduler_sync_outbox SET status='pending',
                            attempts=LEAST(attempts+1,1000000),next_attempt_at=now()+interval '30 seconds',
                            last_error=?,updated_at=now() WHERE resource_id=? AND generation=?
                        """,message.substring(0,Math.min(1024,message.length())),id,item.generation());
                return failure;
            }
        });
        if (error!=null) throw new IllegalStateException("定义已保存，调度同步待持久补偿",error);
    }
    public void retryPending() {
        List<Long> ids=jdbc.query("""
                SELECT resource_id FROM quality.scheduler_sync_outbox
                WHERE status='pending' AND next_attempt_at<=now() ORDER BY resource_id LIMIT 20
                """,(rs,row)->rs.getLong(1));
        for(Long id:ids) {
            try { reconcile(id); }
            catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Schedule {} remains pending",id,failure); }
        }
        // A successfully removed job is retained as a tombstone for 30 days.
        // Pending deletes are never pruned; old triggers also require matching current generation.
        jdbc.update("""
                DELETE FROM quality.scheduler_sync_outbox WHERE deleted AND status='applied'
                    AND applied_at<now()-interval '30 days'
                """);
    }
    public record DesiredSchedule(long id,long generation,boolean deleted,String name,String cron,boolean enabled) { }
}