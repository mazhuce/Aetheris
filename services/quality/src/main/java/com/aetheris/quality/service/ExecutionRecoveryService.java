package com.aetheris.quality.service;

import com.aetheris.quality.entity.QualityCheckResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ExecutionRecoveryService {
    private final JdbcTemplate jdbc;
    private final String owner=UUID.randomUUID().toString();
    public ExecutionRecoveryService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public void claim(Long id) {
        if(id==null || jdbc.update("""
            UPDATE quality.quality_check_result SET lease_owner=?,lease_expires_at=clock_timestamp()+INTERVAL '120 seconds'
            WHERE id=? AND status='running' AND lease_owner IS NULL AND lease_expires_at>clock_timestamp()
            """,owner,id)!=1) throw new IllegalStateException("执行租约领取失败，未启动检查");
    }

    @Transactional
    public boolean finish(QualityCheckResult result, com.aetheris.quality.entity.QualityQuarantineRecord quarantine) {
        var owned=jdbc.queryForList("""
            SELECT id FROM quality.quality_check_result WHERE id=? AND status='running' AND lease_owner=?
                AND lease_expires_at>clock_timestamp() FOR UPDATE
            """,result.getId(),owner);
        if(owned.isEmpty()) return false;
        if(quarantine!=null) jdbc.update("""
            INSERT INTO quality.quality_quarantine_record(result_id,rule_id,target_table,violation_count,threshold_value,reason,status)
            VALUES (?,?,?,?,?,?,'open')
            """,quarantine.getResultId(),quarantine.getRuleId(),quarantine.getTargetTable(),quarantine.getViolationCount(),
                quarantine.getThresholdValue(),quarantine.getReason());
        int updated=jdbc.update("""
            UPDATE quality.quality_check_result SET status=?,violation_count=?,threshold_value=?,duration_ms=?,
                message=?,failure_code=?,finished_at=?
            WHERE id=? AND status='running' AND lease_owner=?
            """,result.getStatus(),result.getViolationCount(),result.getThresholdValue(),result.getDurationMs(),
                result.getMessage(),result.getFailureCode(),result.getFinishedAt(),result.getId(),owner);
        if(updated==1) jdbc.update("""
            UPDATE quality.quality_rule SET last_check_status=?,last_violation_count=?,last_error=?,last_checked_at=?,updated_at=clock_timestamp()
            WHERE id=? AND NOT EXISTS (SELECT 1 FROM quality.quality_check_result WHERE rule_id=? AND id>?)
            """,result.getStatus(),result.getViolationCount(),Set.of("error","unknown").contains(result.getStatus()) ? result.getMessage() : null,
                result.getFinishedAt(),result.getRuleId(),result.getRuleId(),result.getId());
        return updated==1;
    }

    @Scheduled(fixedDelayString="${aetheris.execution.heartbeat-ms:30000}")
    @Transactional
    public void heartbeatAndExpire() {
        jdbc.update("""
            WITH expired AS (
                UPDATE quality.quality_check_result SET status='unknown',failure_code='EXECUTION_LEASE_EXPIRED',
                    message='执行进程失去租约，检查与隔离记录待核实',finished_at=clock_timestamp()
                WHERE status='running' AND lease_expires_at<=clock_timestamp() RETURNING id,rule_id
            ), audited AS (INSERT INTO quality.execution_reconciliation_audit(run_id,rule_id,previous_status,resolved_status,actor,evidence)
                SELECT id,rule_id,'running','unknown','system:lease-expiry','租约到期不能证明结果及隔离记录已持久化' FROM expired
                RETURNING run_id)
            UPDATE quality.quality_rule rule SET last_check_status='unknown',last_violation_count=NULL,
                last_error='执行失去租约，检查结果待核实',last_checked_at=clock_timestamp()
            FROM expired WHERE rule.id=expired.rule_id
                AND NOT EXISTS (SELECT 1 FROM quality.quality_check_result newer WHERE newer.rule_id=rule.id AND newer.id>expired.id)
            """);
        jdbc.update("""
            UPDATE quality.quality_check_result SET lease_expires_at=clock_timestamp()+INTERVAL '120 seconds'
            WHERE status='running' AND lease_owner=? AND lease_expires_at>clock_timestamp()
            """,owner);
    }

    public List<PendingResult> pending(int limit) {
        return jdbc.query("""
            SELECT id,rule_id,status,failure_code,message,started_at,finished_at
            FROM quality.quality_check_result WHERE status='unknown' ORDER BY started_at LIMIT ?
            """,(row,index)->new PendingResult(row.getLong("id"),row.getLong("rule_id"),row.getString("status"),
                    row.getString("failure_code"),row.getString("message"),row.getObject("started_at",java.time.OffsetDateTime.class),
                    row.getObject("finished_at",java.time.OffsetDateTime.class)),Math.clamp(limit,1,200));
    }
    public Definition detail(Long id) {
        var rows=jdbc.queryForList("SELECT id,status,execution_snapshot FROM quality.quality_check_result WHERE id=?",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("检查记录不存在");
        return new Definition((String)rows.getFirst().get("status"),(String)rows.getFirst().get("execution_snapshot"));
    }
    public record Definition(String status,String executionSnapshot) { }
    public record PendingResult(Long id,Long ruleId,String status,String failureCode,String message,
                                java.time.OffsetDateTime startedAt,java.time.OffsetDateTime finishedAt) { }

    @Transactional
    public void reconcile(Long id,String status,boolean confirmedRemoteStopped,String evidence,String actor,Long violations) {
        if(status==null || !Set.of("passed","failed","error").contains(status) || !confirmedRemoteStopped
                || evidence==null || evidence.trim().length()<16 || evidence.length()>2000
                || actor==null || actor.isBlank()) throw new IllegalArgumentException("请确认远端已结束并提供16至2000字的对账依据");
        var rows=jdbc.queryForList("SELECT rule_id,status,threshold_value,manifest_sealed_at,execution_snapshot FROM quality.quality_check_result WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("检查记录不存在");
        var result=rows.getFirst();
        if(!"unknown".equals(result.get("status"))) throw new IllegalStateException("只有结果不确定的检查允许人工对账");
        long threshold=result.get("threshold_value") instanceof Number n ? n.longValue() : 0L;
        if((violations!=null && violations<0) || (!"error".equals(status) && (violations==null || "passed".equals(status)!=(violations<=threshold))))
            throw new IllegalArgumentException("违规计数与检查状态或原始阈值不一致");
        Long ruleId=((Number)result.get("rule_id")).longValue();
        if("failed".equals(status) && jdbc.queryForObject("SELECT COUNT(*) FROM quality.quality_quarantine_record WHERE result_id=?",Long.class,id)==0) {
            String target;
            if(result.get("manifest_sealed_at")!=null) {
                var snapshot=com.aetheris.quality.fixedexecution.FixedJson.parse((String)result.get("execution_snapshot"));
                com.aetheris.quality.fixedexecution.FixedJson.fields(snapshot,"schemaVersion","datasourceId","targetTable","ruleType","expression","threshold");
                if(com.aetheris.quality.fixedexecution.FixedJson.number(snapshot,"schemaVersion",1)!=1)throw com.aetheris.quality.fixedexecution.FixedJson.invalid();
                target=com.aetheris.quality.fixedexecution.FixedJson.text(snapshot,"targetTable");
                // A sealed run never falls back to a mutable rule, including malformed historical snapshots.
                if(!target.matches("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*){0,2}"))throw com.aetheris.quality.fixedexecution.FixedJson.invalid();
            } else target=jdbc.queryForObject("SELECT target_table FROM quality.quality_rule WHERE id=?",String.class,ruleId);
            jdbc.update("""
                INSERT INTO quality.quality_quarantine_record(result_id,rule_id,target_table,violation_count,threshold_value,reason,status)
                VALUES (?,?,?,?,?,?,'open')
                """,id,ruleId,target,violations,threshold,"人工对账："+evidence.substring(0,Math.min(480,evidence.length())));
        }
        jdbc.update("""
            UPDATE quality.quality_check_result SET status=?,violation_count=?,failure_code='MANUALLY_RECONCILED',
                message=?,finished_at=clock_timestamp() WHERE id=? AND status='unknown'
            """,status,violations,"人工对账："+evidence.substring(0,Math.min(480,evidence.length())),id);
        jdbc.update("""
            INSERT INTO quality.execution_reconciliation_audit(run_id,rule_id,previous_status,resolved_status,actor,evidence)
            VALUES (?,?,'unknown',?,?,?)
            """,id,ruleId,status,actor,evidence.trim());
        jdbc.update("""
            UPDATE quality.quality_rule SET last_check_status=?,last_violation_count=?,last_error=?,last_checked_at=clock_timestamp()
            WHERE id=? AND NOT EXISTS (SELECT 1 FROM quality.quality_check_result WHERE rule_id=? AND id>?)
            """,status,violations,"人工对账完成",ruleId,ruleId,id);
    }
}
