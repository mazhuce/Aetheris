package com.aetheris.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves a handle-less unknown Spark result with an immutable operator audit record. */
@Service
public class ExecutionResolutionService {

    private static final Set<String> TARGET_STATUSES = Set.of("success", "failed", "cancelled");
    private static final int MAX_TEXT_LENGTH = 512;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public ExecutionResolutionService(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public ResolutionView resolve(
            long instanceId, String requestedStatus, String reason, String evidenceRef, String resolvedBy) {
        String targetStatus = required(requestedStatus, "必须选择核对结果").toLowerCase();
        if (!TARGET_STATUSES.contains(targetStatus)) {
            throw new IllegalArgumentException("核对结果只允许 success、failed 或 cancelled");
        }
        String normalizedReason = boundedRequired(reason, "必须填写核对原因");
        String normalizedEvidence = boundedRequired(evidenceRef, "必须填写外部证据引用");
        String actor = required(resolvedBy, "缺少核对操作人");
        if (actor.length() > 64) throw new IllegalArgumentException("核对操作人长度不能超过 64 个字符");

        ResolutionView result = transactions.execute(status -> resolveLocked(
                instanceId, targetStatus, normalizedReason, normalizedEvidence, actor));
        if (result == null) throw new IllegalStateException("结果认领事务未返回记录");
        return result;
    }

    private ResolutionView resolveLocked(
            long instanceId, String targetStatus, String reason, String evidenceRef, String resolvedBy) {
        List<UnknownAttempt> rows = jdbc.query("""
                SELECT instance.id instance_id,instance.active_attempt_id,instance.status instance_status,
                       attempt.status attempt_status,attempt.task_type,attempt.remote_handle
                FROM workflow.task_instance instance
                JOIN workflow.execution_attempt attempt ON attempt.id=instance.active_attempt_id
                WHERE instance.id=?
                FOR UPDATE OF instance,attempt
                """, (rs, row) -> new UnknownAttempt(
                rs.getLong("instance_id"), rs.getLong("active_attempt_id"),
                rs.getString("instance_status"), rs.getString("attempt_status"),
                rs.getString("task_type"), rs.getString("remote_handle")), instanceId);
        if (rows.isEmpty()) throw new ResolutionNotFoundException("执行实例或 active attempt 不存在");

        UnknownAttempt current = rows.getFirst();
        if (!"unknown".equals(current.instanceStatus()) || !"unknown".equals(current.attemptStatus())) {
            throw new ResolutionConflictException("只有实例和 active attempt 均为 unknown 时才能认领结果");
        }
        if (!"spark".equals(current.taskType())) {
            throw new ResolutionConflictException("结果认领仅适用于受管 Spark 执行");
        }
        if (StringUtils.hasText(current.remoteHandle())) {
            throw new ResolutionConflictException("该执行已有受管句柄，必须等待自动对账，不能人工覆盖");
        }

        ResolutionView resolution = jdbc.queryForObject("""
                INSERT INTO workflow.execution_resolution
                    (instance_id,attempt_id,from_status,resolved_status,reason,evidence_ref,resolved_by)
                VALUES (?,?,'unknown',?,?,?,?)
                RETURNING id,instance_id,attempt_id,from_status,resolved_status,reason,
                          evidence_ref,resolved_by,created_at
                """, (rs, row) -> new ResolutionView(
                rs.getLong("id"), rs.getLong("instance_id"), rs.getLong("attempt_id"),
                rs.getString("from_status"), rs.getString("resolved_status"),
                rs.getString("reason"), rs.getString("evidence_ref"),
                rs.getString("resolved_by"), rs.getObject("created_at", OffsetDateTime.class)),
                current.instanceId(), current.attemptId(), targetStatus, reason, evidenceRef, resolvedBy);
        if (resolution == null) throw new IllegalStateException("结果认领审计记录未创建");

        String attemptStatus = "success".equals(targetStatus) ? "succeeded" : targetStatus;
        String errorCode = "success".equals(targetStatus) ? null : "MANUAL_RESOLUTION";
        String errorMessage = "success".equals(targetStatus) ? null : reason;
        int attemptUpdated = jdbc.update("""
                UPDATE workflow.execution_attempt
                SET status=?,finished_at=now(),
                    duration_ms=COALESCE(duration_ms,
                        GREATEST(0,(EXTRACT(EPOCH FROM (now()-COALESCE(started_at,queued_at)))*1000)::bigint)),
                    error_code=?,error_message=?,heartbeat_at=now(),lease_expires_at=NULL,
                    cancellation_confirmed_at=CASE WHEN ?='cancelled' THEN now()
                        ELSE cancellation_confirmed_at END,
                    result_metadata_json=result_metadata_json || ?::jsonb
                WHERE id=? AND instance_id=? AND status='unknown'
                """, attemptStatus, errorCode, errorMessage, targetStatus,
                json(Map.of("manualResolutionId", resolution.id(), "evidenceRef", evidenceRef)),
                current.attemptId(), current.instanceId());
        if (attemptUpdated != 1) throw new ResolutionConflictException("attempt 状态已变化，请刷新后重试");

        int instanceUpdated = jdbc.update("""
                UPDATE workflow.task_instance
                SET status=?,finished_at=now(),
                    duration_ms=COALESCE(duration_ms,
                        GREATEST(0,(EXTRACT(EPOCH FROM (now()-COALESCE(started_at,created_at)))*1000)::bigint)),
                    error_message=?,heartbeat_at=now(),lease_expires_at=NULL,updated_at=now()
                WHERE id=? AND active_attempt_id=? AND status='unknown'
                """, targetStatus, errorMessage, current.instanceId(), current.attemptId());
        if (instanceUpdated != 1) throw new ResolutionConflictException("实例状态已变化，请刷新后重试");

        jdbc.update("""
                INSERT INTO workflow.execution_event
                    (attempt_id,event_type,from_status,to_status,message,details_json)
                VALUES (?,'manual_resolution','unknown',?,?,?::jsonb)
                """, current.attemptId(), attemptStatus, reason,
                json(Map.of("resolutionId", resolution.id(), "resolvedBy", resolvedBy,
                        "evidenceRef", evidenceRef, "instanceStatus", targetStatus)));
        return resolution;
    }

    public Optional<ResolutionView> find(long instanceId) {
        List<ResolutionView> rows = jdbc.query("""
                SELECT id,instance_id,attempt_id,from_status,resolved_status,reason,
                       evidence_ref,resolved_by,created_at
                FROM workflow.execution_resolution
                WHERE instance_id=? ORDER BY id DESC LIMIT 1
                """, (rs, row) -> new ResolutionView(
                rs.getLong("id"), rs.getLong("instance_id"), rs.getLong("attempt_id"),
                rs.getString("from_status"), rs.getString("resolved_status"),
                rs.getString("reason"), rs.getString("evidence_ref"),
                rs.getString("resolved_by"), rs.getObject("created_at", OffsetDateTime.class)), instanceId);
        return rows.stream().findFirst();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("核对记录无法序列化", error);
        }
    }

    private static String boundedRequired(String value, String message) {
        String result = required(value, message);
        if (result.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("核对原因和证据引用不能超过 512 个字符");
        }
        return result;
    }

    private static String required(String value, String message) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private record UnknownAttempt(
            long instanceId, long attemptId, String instanceStatus, String attemptStatus,
            String taskType, String remoteHandle) { }

    public record ResolutionView(
            long id, long instanceId, long attemptId, String fromStatus, String resolvedStatus,
            String reason, String evidenceRef, String resolvedBy, OffsetDateTime createdAt) { }

    public static class ResolutionNotFoundException extends IllegalArgumentException {
        public ResolutionNotFoundException(String message) { super(message); }
    }

    public static class ResolutionConflictException extends IllegalStateException {
        public ResolutionConflictException(String message) { super(message); }
    }
}
