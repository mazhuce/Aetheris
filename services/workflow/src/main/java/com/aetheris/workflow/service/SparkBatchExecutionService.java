package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.entity.TaskLog;
import com.aetheris.workflow.service.ExecutionAdapter.LogPage;
import com.aetheris.workflow.service.ExecutionAdapter.ManagedHandle;
import com.aetheris.workflow.service.ExecutionAdapter.RemoteExecution;
import com.aetheris.workflow.service.ExecutionAdapter.Submission;
import com.aetheris.workflow.service.ExecutionClaimCoordinator.ExecutionClaim;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Submits and reconciles managed Spark attempts without ever guessing a POST outcome. */
@Service
public class SparkBatchExecutionService {
    private static final Logger log = LoggerFactory.getLogger(SparkBatchExecutionService.class);
    private static final int LOG_PAGE_BYTES = 32 * 1024;

    private final ManagedSparkCatalogService catalog;
    private final ExecutionAdapter adapter;
    private final ExecutionClaimCoordinator coordinator;
    private final TaskLogService logService;
    private final JdbcTemplate jdbc;
    private final LineageEvidenceClient lineage;

    public SparkBatchExecutionService(ManagedSparkCatalogService catalog,
                                      SparkBatchExecutionAdapter adapter,
                                      ExecutionClaimCoordinator coordinator,
                                      TaskLogService logService,
                                      JdbcTemplate jdbc,
                                      LineageEvidenceClient lineage) {
        this.catalog = catalog;
        this.adapter = adapter;
        this.coordinator = coordinator;
        this.logService = logService;
        this.jdbc = jdbc;
        this.lineage = lineage;
    }

    public TaskInstance submit(Task task, ExecutionClaim claim) {
        SparkJobView job = catalog.runnableJob(task.getJobDefinitionId());
        coordinator.prepareSparkAttempt(claim, task, job);
        String requestKey = requestKey(claim);
        try {
            ManagedHandle handle = adapter.submit(new Submission(requestKey, job.artifactKey(),
                    job.artifactDigest(), Map.of("bizDate", claim.instance().getBizDate(),
                            "attemptId", claim.attemptId()), job.driverCores(),
                    job.driverMemoryMb(), job.timeoutSeconds()));
            TaskInstance instance = coordinator.submitted(claim, handle);
            writeLog(claim, "info", "Spark 作业已提交，受管句柄 " + handle.id());
            return instance;
        } catch (SparkRunnerException error) {
            if (error.isSubmissionOutcomeUnknown()) {
                writeLog(claim, "warn", "提交结果未知，后台将按幂等键核对，禁止重复提交");
                return coordinator.submissionUnknown(claim, error.getMessage());
            }
            writeLog(claim, "error", error.getMessage());
            return coordinator.fail(claim, error.getMessage(), 0, 0);
        }
    }

    @Scheduled(fixedDelayString = "${aetheris.spark-runner.poll-interval-ms:5000}")
    public void reconcileActiveAttempts() {
        for (TrackedAttempt attempt : activeAttempts()) {
            try {
                reconcile(attempt);
            } catch (ExecutionClaimCoordinator.StaleExecutionException stale) {
                log.debug("Spark attempt {} was superseded", attempt.claim().attemptId());
            } catch (RuntimeException error) {
                log.warn("Spark attempt {} reconciliation failed: {}",
                        attempt.claim().attemptId(), error.getMessage());
            }
        }
    }

    public TaskInstance cancel(long instanceId) {
        TrackedAttempt attempt = tracked(instanceId);
        if (attempt == null || attempt.remoteHandle() == null) {
            throw new IllegalStateException("该实例没有可取消的 Spark 运行句柄");
        }
        coordinator.requestCancel(attempt.claim());
        RemoteExecution remote = adapter.cancel(attempt.remoteHandle());
        syncLogs(attempt.withStatus("cancel_requested"));
        if (remote.terminal()) return coordinator.completeRemote(attempt.claim(), remote);
        coordinator.heartbeat(attempt.claim(), remote.applicationId());
        return attempt.claim().instance();
    }

    public LogPage remoteLogs(long instanceId, long offset, int limit) {
        TrackedAttempt attempt = tracked(instanceId);
        if (attempt == null || attempt.remoteHandle() == null) {
            throw new IllegalArgumentException("该实例没有 Spark 日志");
        }
        return adapter.logs(attempt.remoteHandle(), offset, limit);
    }

    private void reconcile(TrackedAttempt attempt) {
        TrackedAttempt resolved = attempt;
        if (resolved.remoteHandle() == null) {
            ManagedHandle handle = adapter.reconcile(requestKey(resolved.claim())).orElse(null);
            if (handle == null) return; // unknown stays unknown; never issue another POST.
            coordinator.submitted(resolved.claim(), handle);
            resolved = resolved.withHandle(handle.id());
        }
        try {
            syncLogs(resolved);
        } catch (RuntimeException logFailure) {
            log.warn("Spark log sync failed for attempt {}: {}",
                    resolved.claim().attemptId(), logFailure.getMessage());
        }
        if ("unknown".equals(resolved.status())) {
            ManagedHandle recovered = new ManagedHandle(resolved.remoteHandle(), requestKey(resolved.claim()),
                    "running", null, null);
            coordinator.submitted(resolved.claim(), recovered);
            resolved = resolved.withStatus("running");
        }
        RemoteExecution remote = adapter.status(resolved.remoteHandle());
        if (remote.terminal()) {
            coordinator.completeRemote(resolved.claim(), remote);
            if ("succeeded".equals(remote.status()) && resolved.jobDefinitionId() != null) {
                lineage.spark(resolved.claim().instance().getTaskId(), resolved.claim(),
                        catalog.job(resolved.jobDefinitionId()));
            }
        } else {
            coordinator.heartbeat(resolved.claim(), remote.applicationId());
        }
    }

    private void syncLogs(TrackedAttempt attempt) {
        long cursor = Math.max(0, attempt.logCursor());
        LogPage page = adapter.logs(attempt.remoteHandle(), cursor, LOG_PAGE_BYTES);
        if (page.content() != null && !page.content().isBlank()) {
            String bounded = page.content().length() > LOG_PAGE_BYTES
                    ? page.content().substring(0, LOG_PAGE_BYTES) : page.content();
            for (String line : bounded.split("\\R")) {
                if (!line.isBlank()) writeLog(attempt.claim(), "info", "[spark] " + line);
            }
        }
        coordinator.updateLogCursor(attempt.claim().attemptId(), page.nextOffset());
    }

    private List<TrackedAttempt> activeAttempts() {
        return jdbc.query(selectTracked() + " WHERE attempt.task_type='spark' "
                + "AND attempt.status IN ('queued','submitting','running','cancel_requested','unknown') "
                + "ORDER BY attempt.id", this::mapTracked);
    }

    private TrackedAttempt tracked(long instanceId) {
        List<TrackedAttempt> rows = jdbc.query(selectTracked()
                + " WHERE instance.id=? AND instance.active_attempt_id=attempt.id",
                this::mapTracked, instanceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String selectTracked() {
        return """
                SELECT instance.id instance_id, instance.task_id, instance.biz_date,
                       instance.status instance_status, instance.trigger_type, instance.attempt,
                       instance.started_at instance_started_at, instance.finished_at instance_finished_at,
                       instance.duration_ms instance_duration_ms, instance.affected_rows instance_affected_rows,
                       instance.error_message instance_error_message, instance.triggered_by instance_triggered_by,
                       instance.active_attempt_id, instance.fencing_token instance_fencing_token,
                       instance.heartbeat_at instance_heartbeat_at,
                       instance.lease_expires_at instance_lease_expires_at,
                       instance.created_at instance_created_at, instance.updated_at instance_updated_at,
                       attempt.id attempt_id, attempt.remote_handle, attempt.log_cursor,
                       attempt.job_definition_id,
                       attempt.status attempt_status
                FROM workflow.execution_attempt attempt
                JOIN workflow.task_instance instance ON instance.id=attempt.instance_id
                """;
    }

    private TrackedAttempt mapTracked(ResultSet rs, int row) throws SQLException {
        TaskInstance instance = new TaskInstance();
        instance.setId(rs.getLong("instance_id")); instance.setTaskId(rs.getLong("task_id"));
        instance.setBizDate(rs.getString("biz_date")); instance.setStatus(rs.getString("instance_status"));
        instance.setTriggerType(rs.getString("trigger_type")); instance.setAttempt(rs.getInt("attempt"));
        instance.setStartedAt(rs.getObject("instance_started_at", OffsetDateTime.class));
        instance.setFinishedAt(rs.getObject("instance_finished_at", OffsetDateTime.class));
        instance.setDurationMs((Long) rs.getObject("instance_duration_ms"));
        instance.setAffectedRows((Long) rs.getObject("instance_affected_rows"));
        instance.setErrorMessage(rs.getString("instance_error_message"));
        instance.setTriggeredBy(rs.getString("instance_triggered_by"));
        instance.setActiveAttemptId((Long) rs.getObject("active_attempt_id"));
        instance.setFencingToken(rs.getLong("instance_fencing_token"));
        instance.setHeartbeatAt(rs.getObject("instance_heartbeat_at", OffsetDateTime.class));
        instance.setLeaseExpiresAt(rs.getObject("instance_lease_expires_at", OffsetDateTime.class));
        instance.setCreatedAt(rs.getObject("instance_created_at", OffsetDateTime.class));
        instance.setUpdatedAt(rs.getObject("instance_updated_at", OffsetDateTime.class));
        long attemptId = rs.getLong("attempt_id");
        ExecutionClaim claim = new ExecutionClaim(instance, attemptId, instance.getAttempt(),
                instance.getFencingToken(), instance.getLeaseExpiresAt());
        return new TrackedAttempt(claim, rs.getString("remote_handle"), rs.getLong("log_cursor"),
                rs.getString("attempt_status"), (Long) rs.getObject("job_definition_id"));
    }

    private void writeLog(ExecutionClaim claim, String level, String message) {
        try {
            TaskLog entry = new TaskLog();
            entry.setInstanceId(claim.instance().getId());
            entry.setAttemptId(claim.attemptId());
            entry.setLevel(level);
            entry.setMessage(message.length() > 4000 ? message.substring(0, 4000) : message);
            logService.save(entry);
        } catch (RuntimeException error) {
            log.warn("Could not persist Spark log for attempt {}", claim.attemptId());
        }
    }

    private static String requestKey(ExecutionClaim claim) {
        return "attempt-" + claim.attemptId() + "-f" + claim.fencingToken();
    }

    private record TrackedAttempt(ExecutionClaim claim, String remoteHandle, long logCursor,
                                  String status, Long jobDefinitionId) {
        TrackedAttempt withHandle(String value) { return new TrackedAttempt(claim, value, logCursor, status, jobDefinitionId); }
        TrackedAttempt withStatus(String value) { return new TrackedAttempt(claim, remoteHandle, logCursor, value, jobDefinitionId); }
    }
}
