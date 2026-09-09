package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.ExecutionAdapter.ManagedHandle;
import com.aetheris.workflow.service.ExecutionAdapter.RemoteExecution;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobView;
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

/** Atomic claim, lease and fencing owner for every physical execution attempt. */
@Service
public class ExecutionClaimCoordinator {

    private static final String ACTIVE_STATES = "'queued','submitting','running','cancel_requested'";
    private static final String BLOCKING_STATES = ACTIVE_STATES + ",'unknown'";
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
        if (leaseGraceSeconds < 1) throw new IllegalStateException("Execution lease grace must be positive");
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
        String initialStatus = "spark".equals(task.getTaskType()) ? "submitting" : "running";
        String claimSql = """
                INSERT INTO workflow.task_instance
                    (task_id, biz_date, status, trigger_type, attempt, started_at, finished_at,
                     duration_ms, affected_rows, error_message, triggered_by, created_at, updated_at,
                     fencing_token, heartbeat_at, lease_expires_at)
                VALUES (?, ?, ?, ?, 1, now(), NULL, NULL, NULL, NULL, ?, now(), now(), 1, now(), ?)
                ON CONFLICT (task_id, biz_date) DO UPDATE SET
                    status = EXCLUDED.status, trigger_type = EXCLUDED.trigger_type,
                    attempt = task_instance.attempt + 1, started_at = now(), finished_at = NULL,
                    duration_ms = NULL, affected_rows = NULL, error_message = NULL,
                    triggered_by = EXCLUDED.triggered_by, updated_at = now(),
                    fencing_token = task_instance.fencing_token + 1, heartbeat_at = now(),
                    lease_expires_at = EXCLUDED.lease_expires_at
                -- Unknown may still represent a live remote Spark application. It is
                -- never reclaimed by lease expiry; the reconciler must resolve it first.
                WHERE task_instance.status NOT IN (""" + BLOCKING_STATES + """
                    ) OR (task_instance.status IN (""" + ACTIVE_STATES + """
                       ) AND task_instance.lease_expires_at IS NOT NULL
                          AND task_instance.lease_expires_at < now())
                RETURNING id, task_id, biz_date, status, trigger_type, attempt, started_at,
                          finished_at, duration_ms, affected_rows, error_message, triggered_by,
                          active_attempt_id, fencing_token, heartbeat_at, lease_expires_at,
                          created_at, updated_at
                """;

        List<TaskInstance> claimed = jdbc.query(claimSql, (rs, rowNum) -> instance(rs),
                task.getId(), bizDate, initialStatus, triggerType, triggeredBy, leaseExpiresAt);
        if (claimed.isEmpty()) {
            throw new IllegalStateException("该任务在此业务日期已有活动执行或待核对结果，不能重复执行");
        }

        TaskInstance instance = claimed.getFirst();
        String parametersJson = json(Map.of("bizDate", bizDate));
        String definitionDigest = digest(String.join("\n",
                String.valueOf(task.getId()), safe(task.getName()), safe(task.getTaskType()),
                safe(task.getTaskSql()), String.valueOf(task.getDatasourceId()),
                String.valueOf(task.getJobDefinitionId()), String.valueOf(task.getQualityRuleId()),
                String.valueOf(task.getUpdatedAt())));
        Long attemptId = jdbc.queryForObject("""
                INSERT INTO workflow.execution_attempt
                    (instance_id, attempt_no, status, fencing_token, worker_id, trigger_type,
                     triggered_by, task_name, task_type, task_sql, datasource_id,
                     job_definition_id, quality_rule_id, task_updated_at, definition_digest,
                     parameters_json, parameter_digest, queued_at, submitted_at, started_at,
                     heartbeat_at, lease_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now(),
                        CASE WHEN ?='running' THEN now() ELSE NULL END,
                        CASE WHEN ?='running' THEN now() ELSE NULL END, now(), ?)
                RETURNING id
                """, Long.class,
                instance.getId(), instance.getAttempt(), initialStatus, instance.getFencingToken(),
                workerId, triggerType, triggeredBy, task.getName(), task.getTaskType(), task.getTaskSql(),
                task.getDatasourceId(), task.getJobDefinitionId(), task.getQualityRuleId(), task.getUpdatedAt(),
                definitionDigest, parametersJson, digest(parametersJson), initialStatus, initialStatus,
                leaseExpiresAt);

        int linked = jdbc.update("""
                UPDATE workflow.task_instance SET active_attempt_id=?, updated_at=now()
                WHERE id=? AND fencing_token=?
                """, attemptId, instance.getId(), instance.getFencingToken());
        if (linked != 1) throw new StaleExecutionException("执行抢占在创建 attempt 时已失效");
        instance.setActiveAttemptId(attemptId);
        event(attemptId, "claimed", null, initialStatus, "执行权已由数据库原子授予", Map.of());
        return new ExecutionClaim(instance, attemptId, instance.getAttempt(),
                instance.getFencingToken(), leaseExpiresAt);
    }

    public void prepareSparkAttempt(ExecutionClaim claim, Task task, SparkJobView job) {
        String definition = digest(String.join("|", safe(task.getName()), String.valueOf(task.getUpdatedAt()),
                job.definitionDigest(), job.artifactDigest()));
        String inputs = json(Map.of("tables", job.inputTables(), "bizDate", claim.instance().getBizDate()));
        String outputs = json(Map.of("uri", job.outputUri() + "/biz_date=" + claim.instance().getBizDate(),
                "writeMode", job.writeMode()));
        int updated = jdbc.update("""
                UPDATE workflow.execution_attempt
                SET job_definition_id=?, job_version=?, artifact_digest=?, definition_digest=?,
                    input_partitions_json=?::jsonb, output_partitions_json=?::jsonb
                WHERE id=? AND fencing_token=? AND status='submitting'
                """, job.id(), job.version(), job.artifactDigest(), definition, inputs, outputs,
                claim.attemptId(), claim.fencingToken());
        if (updated != 1) throw new StaleExecutionException("Spark attempt 在准备提交时已失效");
    }

    public TaskInstance submitted(ExecutionClaim claim, ManagedHandle handle) {
        return transactions.execute(status -> {
            int attempt = jdbc.update("""
                    UPDATE workflow.execution_attempt
                    SET status='running', remote_handle=?, application_id=?, submitted_at=now(),
                        started_at=COALESCE(started_at, now()), heartbeat_at=now()
                    WHERE id=? AND fencing_token=? AND status IN ('queued','submitting','unknown')
                    """, handle.id(), handle.applicationId(), claim.attemptId(), claim.fencingToken());
            if (attempt != 1) throw new StaleExecutionException("Spark 提交结果属于已失效 attempt");
            int instanceUpdated = jdbc.update("""
                    UPDATE workflow.task_instance SET status='running', heartbeat_at=now(), updated_at=now()
                    WHERE id=? AND active_attempt_id=? AND fencing_token=?
                    """, claim.instance().getId(), claim.attemptId(), claim.fencingToken());
            if (instanceUpdated != 1) throw new StaleExecutionException("Spark 提交结果不能覆盖新实例");
            claim.instance().setStatus("running");
            event(claim.attemptId(), "submitted", "submitting", "running",
                    "Spark 运行器返回受管句柄", Map.of("remoteHandle", handle.id()));
            return claim.instance();
        });
    }

    public TaskInstance submissionUnknown(ExecutionClaim claim, String message) {
        return changeActiveState(claim, "unknown", "unknown", "SUBMISSION_UNKNOWN", message,
                "submission_unknown");
    }

    public void heartbeat(ExecutionClaim claim, String applicationId) {
        OffsetDateTime lease = OffsetDateTime.now().plusSeconds(Math.max(leaseGraceSeconds, 30));
        int updated = jdbc.update("""
                UPDATE workflow.execution_attempt
                SET heartbeat_at=now(), lease_expires_at=?, application_id=COALESCE(?, application_id)
                WHERE id=? AND fencing_token=? AND status IN (""" + ACTIVE_STATES + ")",
                lease, applicationId, claim.attemptId(), claim.fencingToken());
        if (updated == 1) {
            jdbc.update("""
                    UPDATE workflow.task_instance SET heartbeat_at=now(), lease_expires_at=?, updated_at=now()
                    WHERE id=? AND active_attempt_id=? AND fencing_token=?
                    """, lease, claim.instance().getId(), claim.attemptId(), claim.fencingToken());
        }
    }

    public TaskInstance requestCancel(ExecutionClaim claim) {
        return changeActiveState(claim, "cancel_requested", "cancel_requested", null,
                "正在请求远端取消", "cancel_requested");
    }

    public TaskInstance completeRemote(ExecutionClaim claim, RemoteExecution remote) {
        OffsetDateTime started = claim.instance().getStartedAt() == null
                ? OffsetDateTime.now() : claim.instance().getStartedAt();
        long duration = Math.max(0, java.time.Duration.between(started, OffsetDateTime.now()).toMillis());
        String result = json(remote.result() == null ? Map.of() : remote.result());
        return switch (remote.status()) {
            case "succeeded" -> complete(claim, "succeeded", "success", remote.affectedRows(), null,
                    null, duration, duration, result, null);
            case "cancelled" -> complete(claim, "cancelled", "cancelled", null, null,
                    null, duration, duration, result, remote.cancellationConfirmedAt());
            case "timed_out" -> complete(claim, "timed_out", "timed_out", remote.affectedRows(),
                    remote.errorMessage(), remote.errorCode(), duration, duration, result, null);
            case "unknown" -> complete(claim, "unknown", "unknown", remote.affectedRows(),
                    remote.errorMessage(), remote.errorCode(), duration, duration, result, null);
            default -> complete(claim, "failed", "failed", remote.affectedRows(),
                    remote.errorMessage(), remote.errorCode(), duration, duration, result, null);
        };
    }

    public TaskInstance succeed(
            ExecutionClaim claim, long affectedRows, long attemptDurationMs, long totalDurationMs) {
        return complete(claim, "succeeded", "success", affectedRows, null,
                null, attemptDurationMs, totalDurationMs, "{}", null);
    }

    public TaskInstance fail(
            ExecutionClaim claim, String errorMessage, long attemptDurationMs, long totalDurationMs) {
        return complete(claim, "failed", "failed", null, truncate(errorMessage),
                "EXECUTION_FAILED", attemptDurationMs, totalDurationMs, "{}", null);
    }

    public TaskInstance skip(ExecutionClaim claim, String reason) {
        return complete(claim, "skipped", "skipped", null, truncate(reason),
                null, 0L, 0L, "{}", null);
    }

    private TaskInstance changeActiveState(
            ExecutionClaim claim, String attemptStatus, String instanceStatus,
            String errorCode, String message, String eventType) {
        return transactions.execute(status -> {
            int attempt = jdbc.update("""
                    UPDATE workflow.execution_attempt
                    SET status=?, error_code=?, error_message=?, heartbeat_at=now()
                    WHERE id=? AND fencing_token=? AND status IN (""" + ACTIVE_STATES + ",'unknown')",
                    attemptStatus, errorCode, truncate(message), claim.attemptId(), claim.fencingToken());
            if (attempt != 1) throw new StaleExecutionException("执行状态已失效");
            int instanceUpdated = jdbc.update("""
                    UPDATE workflow.task_instance SET status=?, error_message=?, heartbeat_at=now(), updated_at=now()
                    WHERE id=? AND active_attempt_id=? AND fencing_token=?
                    """, instanceStatus, truncate(message), claim.instance().getId(),
                    claim.attemptId(), claim.fencingToken());
            if (instanceUpdated != 1) throw new StaleExecutionException("执行状态不能覆盖新实例");
            claim.instance().setStatus(instanceStatus);
            claim.instance().setErrorMessage(truncate(message));
            event(claim.attemptId(), eventType, null, attemptStatus, truncate(message), Map.of());
            return claim.instance();
        });
    }

    private TaskInstance complete(
            ExecutionClaim claim, String attemptStatus, String instanceStatus, Long affectedRows,
            String errorMessage, String errorCode, long attemptDurationMs, long totalDurationMs,
            String resultJson, String cancellationConfirmedAt) {
        TaskInstance result = transactions.execute(status -> {
            int attemptUpdated = jdbc.update("""
                    UPDATE workflow.execution_attempt
                    SET status=?, finished_at=now(), duration_ms=?, affected_rows=?, error_code=?,
                        error_message=?, heartbeat_at=now(), result_metadata_json=?::jsonb,
                        cancellation_confirmed_at=CASE WHEN ? IS NULL THEN cancellation_confirmed_at
                            ELSE ?::timestamptz END
                    WHERE id=? AND fencing_token=? AND status IN (""" + ACTIVE_STATES + ",'unknown')",
                    attemptStatus, attemptDurationMs, affectedRows, errorCode, truncate(errorMessage),
                    resultJson, cancellationConfirmedAt, cancellationConfirmedAt,
                    claim.attemptId(), claim.fencingToken());
            if (attemptUpdated != 1) throw new StaleExecutionException("旧执行者不能结束已失效的 attempt");
            int instanceUpdated = jdbc.update("""
                    UPDATE workflow.task_instance
                    SET status=?, finished_at=now(), duration_ms=?, affected_rows=?, error_message=?,
                        heartbeat_at=now(), lease_expires_at=NULL, updated_at=now()
                    WHERE id=? AND active_attempt_id=? AND fencing_token=?
                    """, instanceStatus, totalDurationMs, affectedRows, truncate(errorMessage),
                    claim.instance().getId(), claim.attemptId(), claim.fencingToken());
            if (instanceUpdated != 1) throw new StaleExecutionException("旧执行者不能覆盖新实例状态");
            event(claim.attemptId(), "completed", null, attemptStatus,
                    errorMessage == null ? "执行结束" : truncate(errorMessage), Map.of());
            return loadInstance(claim.instance().getId(), claim.instance());
        });
        if (result == null) throw new IllegalStateException("执行状态事务未返回结果");
        return result;
    }

    public void updateLogCursor(long attemptId, long cursor) {
        jdbc.update("""
                UPDATE workflow.execution_attempt SET log_cursor=GREATEST(log_cursor, ?)
                WHERE id=?
                """, Math.max(0, cursor), attemptId);
    }

    public int reconcileExpiredLeases() {
        Integer updated = transactions.execute(status -> jdbc.update("""
                WITH expired AS (
                    UPDATE workflow.execution_attempt
                    SET status='unknown', error_code='LEASE_EXPIRED',
                        error_message='执行租约过期，远端结果尚未确认', heartbeat_at=now()
                    WHERE status IN (""" + ACTIVE_STATES + """
                    ) AND lease_expires_at IS NOT NULL AND lease_expires_at < now()
                    RETURNING id, instance_id, fencing_token
                )
                UPDATE workflow.task_instance instance
                SET status='unknown', error_message='执行租约过期，正在等待核对', updated_at=now()
                FROM expired
                WHERE instance.id=expired.instance_id
                  AND instance.active_attempt_id=expired.id
                  AND instance.fencing_token=expired.fencing_token
                """));
        return updated == null ? 0 : updated;
    }

    private void event(long attemptId, String type, String from, String to,
                       String message, Map<String, ?> details) {
        jdbc.update("""
                INSERT INTO workflow.execution_event
                    (attempt_id,event_type,from_status,to_status,message,details_json)
                VALUES (?,?,?,?,?,?::jsonb)
                """, attemptId, type, from, to, message, json(details));
    }

    private TaskInstance loadInstance(long id, TaskInstance target) {
        return jdbc.queryForObject("""
                SELECT id, task_id, biz_date, status, trigger_type, attempt, started_at,
                       finished_at, duration_ms, affected_rows, error_message, triggered_by,
                       active_attempt_id, fencing_token, heartbeat_at, lease_expires_at,
                       created_at, updated_at FROM workflow.task_instance WHERE id=?
                """, (rs, rowNum) -> copyInstance(instance(rs), target), id);
    }

    private TaskInstance copyInstance(TaskInstance source, TaskInstance target) {
        TaskInstance value = target == null ? new TaskInstance() : target;
        value.setId(source.getId()); value.setTaskId(source.getTaskId()); value.setBizDate(source.getBizDate());
        value.setStatus(source.getStatus()); value.setTriggerType(source.getTriggerType());
        value.setAttempt(source.getAttempt()); value.setStartedAt(source.getStartedAt());
        value.setFinishedAt(source.getFinishedAt()); value.setDurationMs(source.getDurationMs());
        value.setAffectedRows(source.getAffectedRows()); value.setErrorMessage(source.getErrorMessage());
        value.setTriggeredBy(source.getTriggeredBy()); value.setActiveAttemptId(source.getActiveAttemptId());
        value.setFencingToken(source.getFencingToken()); value.setHeartbeatAt(source.getHeartbeatAt());
        value.setLeaseExpiresAt(source.getLeaseExpiresAt()); value.setCreatedAt(source.getCreatedAt());
        value.setUpdatedAt(source.getUpdatedAt());
        return value;
    }

    private TaskInstance instance(java.sql.ResultSet rs) throws java.sql.SQLException {
        TaskInstance value = new TaskInstance();
        value.setId(rs.getLong("id")); value.setTaskId(rs.getLong("task_id"));
        value.setBizDate(rs.getString("biz_date")); value.setStatus(rs.getString("status"));
        value.setTriggerType(rs.getString("trigger_type")); value.setAttempt(rs.getInt("attempt"));
        value.setStartedAt(rs.getObject("started_at", OffsetDateTime.class));
        value.setFinishedAt(rs.getObject("finished_at", OffsetDateTime.class));
        value.setDurationMs((Long) rs.getObject("duration_ms"));
        value.setAffectedRows((Long) rs.getObject("affected_rows"));
        value.setErrorMessage(rs.getString("error_message")); value.setTriggeredBy(rs.getString("triggered_by"));
        value.setActiveAttemptId((Long) rs.getObject("active_attempt_id"));
        value.setFencingToken(rs.getLong("fencing_token"));
        value.setHeartbeatAt(rs.getObject("heartbeat_at", OffsetDateTime.class));
        value.setLeaseExpiresAt(rs.getObject("lease_expires_at", OffsetDateTime.class));
        value.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        value.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
        return value;
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("执行数据无法序列化", error);
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > MAX_ERROR_LENGTH ? value.substring(0, MAX_ERROR_LENGTH) + "…" : value;
    }

    public record ExecutionClaim(
            TaskInstance instance, Long attemptId, int attemptNo, long fencingToken,
            OffsetDateTime leaseExpiresAt) { }

    public static class StaleExecutionException extends IllegalStateException {
        public StaleExecutionException(String message) { super(message); }
    }
}
