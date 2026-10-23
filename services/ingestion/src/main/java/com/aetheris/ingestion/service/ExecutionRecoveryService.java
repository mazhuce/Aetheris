package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Expiry fences the old worker; it never authorizes replay of an unobserved transfer. */
@Service
public class ExecutionRecoveryService {
    private final JdbcTemplate jdbc;
    private final String owner = UUID.randomUUID().toString();
    public ExecutionRecoveryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void claim(Long id) {
        if (id == null || jdbc.update("""
            UPDATE ingestion.ingestion_run SET lease_owner=?,lease_expires_at=clock_timestamp()+INTERVAL '120 seconds'
            WHERE id=? AND status='running' AND lease_owner IS NULL AND lease_expires_at>clock_timestamp()
            """, owner, id) != 1) throw new IllegalStateException("执行租约领取失败，未启动接入");
    }

    @Transactional
    public boolean finish(IngestionRun run) {
        int updated = jdbc.update("""
            UPDATE ingestion.ingestion_run SET status=?,rows_read=?,rows_written=?,duration_ms=?,
                error_message=?,failure_code=?,finished_at=?
            WHERE id=? AND status='running' AND lease_owner=? AND lease_expires_at>clock_timestamp()
            """, run.getStatus(),run.getRowsRead(),run.getRowsWritten(),run.getDurationMs(),run.getErrorMessage(),
                run.getFailureCode(),run.getFinishedAt(),run.getId(),owner);
        if (updated == 1 && "unknown".equals(run.getStatus())) {
            jdbc.update("""
                INSERT INTO ingestion.execution_reconciliation_audit(run_id,task_id,previous_status,resolved_status,actor,evidence)
                VALUES (?,?,'running','unknown',?,?)
                """,run.getId(),run.getTaskId(),"system:transfer",run.getErrorMessage());
        }
        if(updated==1) jdbc.update("""
            UPDATE ingestion.ingestion_task SET last_run_status=?,last_run_rows=?,last_error=?,last_run_at=?,updated_at=clock_timestamp()
            WHERE id=? AND NOT EXISTS (SELECT 1 FROM ingestion.ingestion_run WHERE task_id=? AND id>?)
            """,run.getStatus(),run.getRowsWritten(),run.getErrorMessage(),run.getFinishedAt(),run.getTaskId(),run.getTaskId(),run.getId());
        return updated == 1;
    }

    @Scheduled(fixedDelayString = "${aetheris.execution.heartbeat-ms:30000}")
    @Transactional
    public void heartbeatAndExpire() {
        jdbc.update("""
            WITH expired AS (
                UPDATE ingestion.ingestion_run SET status='unknown',failure_code='EXECUTION_LEASE_EXPIRED',
                    error_message='执行进程失去租约，远端结果待核实；禁止自动重放',finished_at=clock_timestamp()
                WHERE status='running' AND lease_expires_at<=clock_timestamp() RETURNING id,task_id
            ), audited AS (INSERT INTO ingestion.execution_reconciliation_audit(run_id,task_id,previous_status,resolved_status,actor,evidence)
                SELECT id,task_id,'running','unknown','system:lease-expiry','租约到期不能证明远端未执行' FROM expired
                RETURNING run_id)
            UPDATE ingestion.ingestion_task task SET last_run_status='unknown',last_run_rows=NULL,
                last_error='执行失去租约，远端结果待核实',last_run_at=clock_timestamp()
            FROM expired WHERE task.id=expired.task_id
                AND NOT EXISTS (SELECT 1 FROM ingestion.ingestion_run newer WHERE newer.task_id=task.id AND newer.id>expired.id)
            """);
        jdbc.update("""
            UPDATE ingestion.ingestion_run SET lease_expires_at=clock_timestamp()+INTERVAL '120 seconds'
            WHERE status='running' AND lease_owner=? AND lease_expires_at>clock_timestamp()
            """,owner);
    }

    public List<PendingRun> pending(int limit) {
        return jdbc.query("""
            SELECT id,task_id,status,failure_code,error_message,started_at,finished_at
            FROM ingestion.ingestion_run WHERE status='unknown' ORDER BY started_at LIMIT ?
            """,(row,index)->new PendingRun(row.getLong("id"),row.getLong("task_id"),row.getString("status"),
                    row.getString("failure_code"),row.getString("error_message"),row.getObject("started_at",java.time.OffsetDateTime.class),
                    row.getObject("finished_at",java.time.OffsetDateTime.class)),Math.clamp(limit,1,200));
    }
    public Definition detail(Long id) {
        var rows=jdbc.queryForList("SELECT id,status,execution_snapshot FROM ingestion.ingestion_run WHERE id=?",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("运行记录不存在");
        return new Definition((String)rows.getFirst().get("status"),(String)rows.getFirst().get("execution_snapshot"));
    }
    public record Definition(String status,String executionSnapshot) { }
    public record PendingRun(Long id,Long taskId,String status,String failureCode,String errorMessage,
                             java.time.OffsetDateTime startedAt,java.time.OffsetDateTime finishedAt) { }

    @Transactional
    public void reconcile(Long id, String status, boolean confirmedRemoteStopped, String evidence,
                          String actor, Long rowsRead, Long rowsWritten) {
        if (status==null || !Set.of("success","failed").contains(status) || !confirmedRemoteStopped
                || evidence == null || evidence.trim().length()<16 || evidence.length()>2000
                || actor == null || actor.isBlank()) throw new IllegalArgumentException("请确认远端已结束并提供16至2000字的对账依据");
        if ((rowsRead!=null && rowsRead<0) || (rowsWritten!=null && rowsWritten<0)
                || ("success".equals(status) && (rowsRead==null || rowsWritten==null)))
            throw new IllegalArgumentException("确认成功必须提供核实后的读取和写入行数");
        var rows=jdbc.queryForList("SELECT task_id,status FROM ingestion.ingestion_run WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("运行记录不存在");
        var run=rows.getFirst();
        if(!"unknown".equals(run.get("status"))) throw new IllegalStateException("只有结果不确定的运行允许人工对账");
        Long taskId=((Number)run.get("task_id")).longValue();
        jdbc.update("""
            UPDATE ingestion.ingestion_run SET status=?,rows_read=?,rows_written=?,failure_code='MANUALLY_RECONCILED',
                error_message=?,finished_at=clock_timestamp() WHERE id=? AND status='unknown'
            """,status,rowsRead,rowsWritten,"人工对账："+evidence.substring(0,Math.min(480,evidence.length())),id);
        jdbc.update("""
            INSERT INTO ingestion.execution_reconciliation_audit(run_id,task_id,previous_status,resolved_status,actor,evidence)
            VALUES (?,?,'unknown',?,?,?)
            """,id,taskId,status,actor,evidence.trim());
        jdbc.update("""
            UPDATE ingestion.ingestion_task SET last_run_status=?,last_run_rows=?,last_error=?,last_run_at=clock_timestamp()
            WHERE id=? AND NOT EXISTS (SELECT 1 FROM ingestion.ingestion_run WHERE task_id=? AND id>?)
            """,status,rowsWritten,"人工对账完成",taskId,taskId,id);
    }
}
