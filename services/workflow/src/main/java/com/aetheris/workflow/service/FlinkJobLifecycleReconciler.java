package com.aetheris.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.OffsetDateTime;

/**
 * Reconciles persisted Flink JobManager identities independently from the SQL
 * Gateway session.  A JobManager outage is an unknown result, never success;
 * a later successful poll can recover a transient unknown back to running.
 */
@Service
public class FlinkJobLifecycleReconciler {
    private static final Logger log = LoggerFactory.getLogger(FlinkJobLifecycleReconciler.class);

    private final JdbcTemplate jdbc;
    private final FlinkJobManagerClient jobManager;
    private final long leaseGraceSeconds;

    @Autowired
    public FlinkJobLifecycleReconciler(JdbcTemplate jdbc, FlinkJobManagerClient jobManager,
            @Value("${aetheris.execution.lease-grace-seconds:60}") long leaseGraceSeconds) {
        this.jdbc = jdbc;
        this.jobManager = jobManager;
        this.leaseGraceSeconds = Math.max(leaseGraceSeconds, 30);
    }

    FlinkJobLifecycleReconciler(JdbcTemplate jdbc, FlinkJobManagerClient jobManager) {
        this(jdbc, jobManager, 60);
    }

    @Scheduled(fixedDelayString = "${aetheris.flink-jobmanager.reconcile-interval-ms:5000}")
    public void reconcileActiveJobs() {
        List<RemoteAttempt> attempts = jdbc.query("""
                SELECT ea.id, ea.instance_id, ea.flink_job_id, ea.status, ea.flink_lifecycle_intent
                FROM workflow.execution_attempt ea
                JOIN workflow.task_instance ti ON ti.id=ea.instance_id
                JOIN workflow.task t ON t.id=ti.task_id
                WHERE t.task_type='flink'
                  AND ti.active_attempt_id=ea.id
                  AND ea.flink_job_id IS NOT NULL
                  AND ea.status IN ('running','cancel_requested','unknown')
                  AND ea.flink_lifecycle_state IN ('running','stopping','upgrading','unknown')
                ORDER BY ea.id
                LIMIT 100
                """, (rs, rowNum) -> new RemoteAttempt(
                rs.getLong("id"), rs.getLong("instance_id"), rs.getString("flink_job_id"),
                rs.getString("status"), rs.getString("flink_lifecycle_intent")));
        for (RemoteAttempt attempt : attempts) {
            try {
                FlinkJobManagerClient.JobStatus status = jobManager.status(attempt.jobId());
                FlinkJobManagerClient.CheckpointSummary checkpoint = jobManager.checkpoints(attempt.jobId());
                reconcile(attempt, status.state(), checkpoint.latestCompletedId());
            } catch (RuntimeException error) {
                markUnknown(attempt, "无法确认 Flink JobManager 状态：" + safeMessage(error));
                log.warn("Flink JobManager state is unknown for attempt {}", attempt.attemptId(), error);
            }
        }
    }

    void reconcile(RemoteAttempt attempt, String remoteState, Long checkpointId) {
        String state = normalize(remoteState);
        String intent = attempt.intent();
        if ("running".equals(state)) {
            OffsetDateTime lease = OffsetDateTime.now().plusSeconds(leaseGraceSeconds);
            jdbc.update("""
                    UPDATE workflow.execution_attempt
                       SET status=CASE WHEN status='unknown' THEN 'running' ELSE status END,
                           flink_lifecycle_state='running',
                           flink_latest_checkpoint_id=COALESCE(?, flink_latest_checkpoint_id),
                           error_code=CASE WHEN status='unknown' THEN NULL ELSE error_code END,
                           error_message=CASE WHEN status='unknown' THEN NULL ELSE error_message END,
                           heartbeat_at=now(), lease_expires_at=?, flink_lifecycle_updated_at=now()
                     WHERE id=? AND instance_id=? AND flink_job_id=?
                       AND status IN ('running','cancel_requested','unknown')
                    """, checkpointId, lease, attempt.attemptId(), attempt.instanceId(), attempt.jobId());
            jdbc.update("""
                    UPDATE workflow.task_instance
                       SET status=CASE WHEN status='unknown' THEN 'running' ELSE status END,
                           error_message=CASE WHEN status='unknown' THEN NULL ELSE error_message END,
                           heartbeat_at=now(), lease_expires_at=?, updated_at=now()
                     WHERE id=? AND active_attempt_id=? AND status IN ('running','cancel_requested','unknown')
                       AND EXISTS (SELECT 1 FROM workflow.execution_attempt ea
                                   WHERE ea.id=? AND ea.flink_job_id=?)
                    """, lease, attempt.instanceId(), attempt.attemptId(), attempt.attemptId(), attempt.jobId());
            return;
        }
        if ("failed".equals(state)) {
            complete(attempt, "failed", "failed", "FLINK_REMOTE_FAILED", "Flink JobManager 报告 Job 失败");
            return;
        }
        if ("cancelled".equals(state)) {
            complete(attempt, "cancelled", "cancelled", null, "Flink JobManager 已确认 Job 取消");
            return;
        }
        if ("stopped".equals(state) && ("pause".equals(intent) || "stop".equals(intent) || "upgrade".equals(intent))) {
            jdbc.update("""
                    UPDATE workflow.execution_attempt
                       SET flink_lifecycle_state='stopped',
                           flink_latest_checkpoint_id=COALESCE(?, flink_latest_checkpoint_id),
                           heartbeat_at=now(), lease_expires_at=NULL, flink_lifecycle_updated_at=now()
                     WHERE id=? AND instance_id=? AND flink_job_id=?
                       AND status IN ('running','cancel_requested','unknown')
                    """, checkpointId, attempt.attemptId(), attempt.instanceId(), attempt.jobId());
            jdbc.update("""
                    UPDATE workflow.task_instance SET lease_expires_at=NULL, updated_at=now()
                    WHERE id=? AND active_attempt_id=?
                      AND EXISTS (SELECT 1 FROM workflow.execution_attempt ea
                                  WHERE ea.id=? AND ea.flink_job_id=?)
                    """, attempt.instanceId(), attempt.attemptId(), attempt.attemptId(), attempt.jobId());
            return;
        }
        // FINISHED without a persisted lifecycle intent is unexpected for an
        // unbounded task, so it is a failure, never a successful completion.
        if ("stopped".equals(state)) {
            complete(attempt, "failed", "failed", "FLINK_UNEXPECTED_FINISH",
                    "Flink 无界任务异常结束，未发现暂停/停止/升级意图");
            return;
        }
        markUnknown(attempt, "Flink JobManager 返回不可判定状态：" + remoteState);
    }

    private void markUnknown(RemoteAttempt attempt, String message) {
        int updated = jdbc.update("""
                UPDATE workflow.execution_attempt
                   SET status='unknown', flink_lifecycle_state='unknown',
                       error_code='FLINK_JOBMANAGER_UNAVAILABLE', error_message=?,
                       heartbeat_at=now(), flink_lifecycle_updated_at=now()
                 WHERE id=? AND instance_id=? AND flink_job_id=?
                   AND status IN ('running','cancel_requested','unknown')
                   AND NOT (status='unknown'
                       AND error_code='FLINK_JOBMANAGER_UNAVAILABLE'
                       AND error_message IS NOT DISTINCT FROM ?)
                """, truncate(message), attempt.attemptId(), attempt.instanceId(), attempt.jobId(), truncate(message));
        if (updated == 1) {
            jdbc.update("""
                    UPDATE workflow.task_instance
                       SET status='unknown', error_message=?, heartbeat_at=now(), updated_at=now()
                     WHERE id=? AND active_attempt_id=? AND status IN ('running','cancel_requested','unknown')
                       AND EXISTS (SELECT 1 FROM workflow.execution_attempt ea
                                   WHERE ea.id=? AND ea.flink_job_id=?)
                    """, truncate(message), attempt.instanceId(), attempt.attemptId(),
                    attempt.attemptId(), attempt.jobId());
            jdbc.update("""
                    INSERT INTO workflow.execution_event
                        (attempt_id,event_type,to_status,message,details_json)
                    VALUES (?, 'flink_job_unknown', 'unknown', ?, '{}'::jsonb)
                    """, attempt.attemptId(), truncate(message));
        }
    }

    private void complete(RemoteAttempt attempt, String attemptStatus, String instanceStatus,
                          String errorCode, String message) {
        int updated = jdbc.update("""
                UPDATE workflow.execution_attempt
                   SET status=?, finished_at=now(), error_code=?, error_message=?,
                       heartbeat_at=now(), lease_expires_at=NULL,
                       cancellation_confirmed_at=CASE WHEN ?='cancelled' THEN now() ELSE cancellation_confirmed_at END,
                       flink_lifecycle_state=?, flink_lifecycle_updated_at=now()
                 WHERE id=? AND instance_id=? AND flink_job_id=?
                   AND status IN ('running','cancel_requested','unknown')
                """, attemptStatus, errorCode, truncate(message), attemptStatus,
                "cancelled".equals(attemptStatus) ? "cancelled" : "failed",
                attempt.attemptId(), attempt.instanceId(), attempt.jobId());
        if (updated == 1) {
            jdbc.update("""
                    UPDATE workflow.task_instance
                       SET status=?, finished_at=now(), error_message=?, lease_expires_at=NULL, updated_at=now()
                     WHERE id=? AND active_attempt_id=? AND status IN ('running','cancel_requested','unknown')
                       AND EXISTS (SELECT 1 FROM workflow.execution_attempt ea
                                   WHERE ea.id=? AND ea.flink_job_id=?)
                    """, instanceStatus, truncate(message), attempt.instanceId(), attempt.attemptId(),
                    attempt.attemptId(), attempt.jobId());
            jdbc.update("""
                    INSERT INTO workflow.execution_event
                        (attempt_id,event_type,to_status,message,details_json)
                    VALUES (?, 'flink_job_reconciled', ?, ?, '{}'::jsonb)
                    """, attempt.attemptId(), attemptStatus, truncate(message));
        }
    }

    private static String normalize(String value) {
        return switch (value == null ? "" : value.toUpperCase()) {
            case "RUNNING", "RESTARTING", "CREATED", "INITIALIZING" -> "running";
            case "CANCELED" -> "cancelled";
            case "FAILED" -> "failed";
            case "FINISHED" -> "stopped";
            default -> "unknown";
        };
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 1000 ? value.substring(0, 1000) + "…" : value;
    }

    record RemoteAttempt(long attemptId, long instanceId, String jobId, String status, String intent) { }
}
