package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Owns the database transaction for claiming and completing execution attempts.
 *
 * <p>The claim is a PostgreSQL upsert with a conditional conflict update. Concurrent
 * callers cannot both observe an idle row and start work. Completion checks both the
 * active attempt id and monotonically increasing fencing token, so an expired worker
 * cannot overwrite a newer attempt.</p>
 */
@Service
public class ExecutionClaimCoordinator {

    private static final String ACTIVE_STATES = "'queued','submitting','running','cancel_requested'";
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final String workerId;
    private final long leaseGraceSeconds;

    public ExecutionClaimCoordinator(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            @Value("${aetheris.execution.worker-id:local}") String workerId,
            @Value("${aetheris.execution.lease-grace-seconds:60}") long leaseGraceSeconds) {
        if (leaseGraceSeconds < 1) {
            throw new IllegalStateException("Execution lease grace must be positive");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.workerId = workerId;
        this.leaseGraceSeconds = leaseGraceSeconds;
    }

    public ExecutionClaim claim(
            Task task, String bizDate, String triggerType, String triggeredBy, int timeoutSeconds) {
        OffsetDateTime leaseExpiresAt = OffsetDateTime.now()
                .plusSeconds(Math.addExact(timeoutSeconds, leaseGraceSeconds));
        return transactions.execute(status -> claimInTransaction(
                task, bizDate, triggerType, triggeredBy, leaseExpiresAt));
    }

    private ExecutionClaim claimInTransaction(
            Task task, String bizDate, String triggerType, String triggeredBy,
            OffsetDateTime leaseExpiresAt) {
        String claimSql = """
                INSERT INTO workflow.task_instance
                    (task_id, biz_date, status, trigger_type, attempt, started_at, finished_at,
                     duration_ms, affected_rows, error_message, triggered_by, created_at, updated_at,
                     fencing_token, heartbeat_at, lease_expires_at)
                VALUES (?, ?, 'running', ?, 1, now(), NULL, NULL, NULL, NULL, ?, now(), now(),
                        1, now(), ?)
                ON CONFLICT (task_id, biz_date) DO UPDATE SET
                    status = 'running',
                    trigger_type = EXCLUDED.trigger_type,
                    attempt = task_instance.attempt + 1,
                    started_at = now(),
                    finished_at = NULL,
                    duration_ms = NULL,
                    affected_rows = NULL,
                    error_message = NULL,
                    triggered_by = EXCLUDED.triggered_by,
                    updated_at = now(),
                    fencing_token = task_instance.fencing_token + 1,
                    heartbeat_at = now(),
                    lease_expires_at = EXCLUDED.lease_expires_at
                WHERE task_instance.status NOT IN (""" + ACTIVE_STATES + """
                    ) OR (task_instance.lease_expires_at IS NOT NULL
                          AND task_instance.lease_expires_at < now())
                RETURNING id, task_id, biz_date, status, trigger_type, attempt, started_at,
                          finished_at, duration_ms, affected_rows, error_message, triggered_by,
                          active_attempt_id, fencing_token, heartbeat_at, lease_expires_at,
                          created_at, updated_at
                """;

        List<TaskInstance> claimed = jdbc.query(claimSql, (rs, rowNum) -> {
            TaskInstance value = new TaskInstance();
            value.setId(rs.getLong("id"));
            value.setTaskId(rs.getLong("task_id"));
            value.setBizDate(rs.getString("biz_date"));
            value.setStatus(rs.getString("status"));
            value.setTriggerType(rs.getString("trigger_type"));
            value.setAttempt(rs.getInt("attempt"));
            value.setStartedAt(rs.getObject("started_at", OffsetDateTime.class));
            value.setFinishedAt(rs.getObject("finished_at", OffsetDateTime.class));
            value.setDurationMs((Long) rs.getObject("duration_ms"));
            value.setAffectedRows((Long) rs.getObject("affected_rows"));
            value.setErrorMessage(rs.getString("error_message"));
            value.setTriggeredBy(rs.getString("triggered_by"));
            value.setActiveAttemptId((Long) rs.getObject("active_attempt_id"));
            value.setFencingToken(rs.getLong("fencing_token"));
            value.setHeartbeatAt(rs.getObject("heartbeat_at", OffsetDateTime.class));
            value.setLeaseExpiresAt(rs.getObject("lease_expires_at", OffsetDateTime.class));
            value.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
            value.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
            return value;
        }, task.getId(), bizDate, triggerType, triggeredBy, leaseExpiresAt);

        if (claimed.isEmpty()) {
            throw new IllegalStateException("该任务在此业务日期已有有效租约，不能重复执行");
        }

        TaskInstance instance = claimed.getFirst();
        String parametersJson = json(Map.of("bizDate", bizDate));
        String definitionDigest = digest(String.join("\n",
                String.valueOf(task.getId()), safe(task.getName()), safe(task.getTaskSql()),
                String.valueOf(task.getDatasourceId()), String.valueOf(task.getUpdatedAt())));
        String parameterDigest = digest(parametersJson);

        Long attemptId = jdbc.queryForObject("""
                INSERT INTO workflow.execution_attempt
                    (instance_id, attempt_no, status, fencing_token, worker_id, trigger_type,
                     triggered_by, task_name, task_sql, datasource_id, task_updated_at,
                     definition_digest, parameters_json, parameter_digest, queued_at,
                     submitted_at, started_at, heartbeat_at, lease_expires_at)
                VALUES (?, ?, 'running', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now(),
                        now(), now(), now(), ?)
                RETURNING id
                """, Long.class,
                instance.getId(), instance.getAttempt(), instance.getFencingToken(), workerId,
                triggerType, triggeredBy, task.getName(), task.getTaskSql(), task.getDatasourceId(),
                task.getUpdatedAt(), definitionDigest, parametersJson, parameterDigest, leaseExpiresAt);

        int linked = jdbc.update("""
                UPDATE workflow.task_instance
                SET active_attempt_id = ?, updated_at = now()
                WHERE id = ? AND fencing_token = ?
                """, attemptId, instance.getId(), instance.getFencingToken());
        if (linked != 1) {
            throw new StaleExecutionException("执行抢占在创建 attempt 时已失效");
        }
        instance.setActiveAttemptId(attemptId);
        return new ExecutionClaim(instance, attemptId, instance.getAttempt(),
                instance.getFencingToken(), leaseExpiresAt);
    }

    public TaskInstance succeed(
            ExecutionClaim claim, long affectedRows, long attemptDurationMs, long totalDurationMs) {
        return complete(claim, "succeeded", "success", affectedRows, null,
                attemptDurationMs, totalDurationMs);
    }

    public TaskInstance fail(
            ExecutionClaim claim, String errorMessage, long attemptDurationMs, long totalDurationMs) {
        return complete(claim, "failed", "failed", null, truncate(errorMessage),
                attemptDurationMs, totalDurationMs);
    }

    public TaskInstance skip(ExecutionClaim claim, String reason) {
        return complete(claim, "skipped", "skipped", null, truncate(reason), 0L, 0L);
    }

    private TaskInstance complete(
            ExecutionClaim claim, String attemptStatus, String instanceStatus, Long affectedRows,
            String errorMessage, long attemptDurationMs, long totalDurationMs) {
        TaskInstance result = transactions.execute(status -> {
            int attemptUpdated = jdbc.update("""
                    UPDATE workflow.execution_attempt
                    SET status = ?, finished_at = now(), duration_ms = ?, affected_rows = ?,
                        error_code = ?, error_message = ?, heartbeat_at = now()
                    WHERE id = ? AND fencing_token = ? AND status IN (
                    """ + ACTIVE_STATES + ")",
                    attemptStatus, attemptDurationMs, affectedRows,
                    errorMessage == null ? null : "SQL_EXECUTION_FAILED", errorMessage,
                    claim.attemptId(), claim.fencingToken());
            if (attemptUpdated != 1) {
                throw new StaleExecutionException("旧执行者不能结束已失效的 attempt");
            }

            int instanceUpdated = jdbc.update("""
                    UPDATE workflow.task_instance
                    SET status = ?, finished_at = now(), duration_ms = ?, affected_rows = ?,
                        error_message = ?, heartbeat_at = now(), lease_expires_at = NULL,
                        updated_at = now()
                    WHERE id = ? AND active_attempt_id = ? AND fencing_token = ?
                    """, instanceStatus, totalDurationMs, affectedRows, errorMessage,
                    claim.instance().getId(), claim.attemptId(), claim.fencingToken());
            if (instanceUpdated != 1) {
                throw new StaleExecutionException("旧执行者不能覆盖新实例状态");
            }
            return jdbc.queryForObject("""
                    SELECT id, task_id, biz_date, status, trigger_type, attempt, started_at,
                           finished_at, duration_ms, affected_rows, error_message, triggered_by,
                           active_attempt_id, fencing_token, heartbeat_at, lease_expires_at,
                           created_at, updated_at
                    FROM workflow.task_instance WHERE id = ?
                    """, (rs, rowNum) -> {
                TaskInstance value = claim.instance();
                value.setStatus(rs.getString("status"));
                value.setFinishedAt(rs.getObject("finished_at", OffsetDateTime.class));
                value.setDurationMs((Long) rs.getObject("duration_ms"));
                value.setAffectedRows((Long) rs.getObject("affected_rows"));
                value.setErrorMessage(rs.getString("error_message"));
                value.setHeartbeatAt(rs.getObject("heartbeat_at", OffsetDateTime.class));
                value.setLeaseExpiresAt(rs.getObject("lease_expires_at", OffsetDateTime.class));
                return value;
            }, claim.instance().getId());
        });
        if (result == null) {
            throw new IllegalStateException("执行状态事务未返回结果");
        }
        return result;
    }

    /** Mark expired workers unknown; a later reconcile step may inspect their remote handle. */
    public int reconcileExpiredLeases() {
        Integer updated = transactions.execute(status -> jdbc.update("""
                WITH expired AS (
                    UPDATE workflow.execution_attempt
                    SET status = 'unknown', error_code = 'LEASE_EXPIRED',
                        error_message = '执行租约过期，远端结果尚未确认', heartbeat_at = now()
                    WHERE status IN (""" + ACTIVE_STATES + """
                    ) AND lease_expires_at IS NOT NULL AND lease_expires_at < now()
                    RETURNING id, instance_id, fencing_token
                )
                UPDATE workflow.task_instance instance
                SET status = 'unknown', error_message = '执行租约过期，正在等待核对',
                    updated_at = now()
                FROM expired
                WHERE instance.id = expired.instance_id
                  AND instance.active_attempt_id = expired.id
                  AND instance.fencing_token = expired.fencing_token
                """));
        return updated == null ? 0 : updated;
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("执行参数无法序列化", error);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_ERROR_LENGTH ? value.substring(0, MAX_ERROR_LENGTH) + "…" : value;
    }

    public record ExecutionClaim(
            TaskInstance instance, Long attemptId, int attemptNo, long fencingToken,
            OffsetDateTime leaseExpiresAt) {
    }

    public static class StaleExecutionException extends IllegalStateException {
        public StaleExecutionException(String message) {
            super(message);
        }
    }
}
