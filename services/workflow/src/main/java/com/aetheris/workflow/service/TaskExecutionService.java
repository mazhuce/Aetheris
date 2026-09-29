package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskDependency;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.entity.TaskLog;
import com.aetheris.workflow.service.ExecutionClaimCoordinator.ExecutionClaim;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务执行器。
 *
 * <p>幂等策略：{@code (task_id, biz_date)} 唯一，同一业务日期复用逻辑实例；每次物理执行
 * 新建 execution_attempt。抢占和结束由 {@link ExecutionClaimCoordinator} 在数据库中原子完成。
 *
 * <p>数据幂等则依赖加工语句本身用 {@code INSERT OVERWRITE ... PARTITION}：覆盖目标分区，
 * 因此重跑不会产生重复数据，也不需要先删表（{@code DROP}/{@code TRUNCATE} 被写入守卫拒绝）。
 *
 * <p>长时间 SQL 不放在状态事务中。租约和 fencing token 让并发触发只能有一个有效执行者，
 * 并阻止过期执行者覆盖新 attempt 的状态。
 */
@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final TaskService taskService;
    private final TaskInstanceService instanceService;
    private final TaskLogService logService;
    private final TaskDependencyService dependencyService;
    private final ExecutionClaimCoordinator claimCoordinator;
    private final SqlExecutionClient executionClient;
    private final NotificationClient notificationClient;
    private final SparkBatchExecutionService sparkExecutionService;
    private final FlinkSqlExecutionClient flinkExecutionClient;
    private final FlinkJobManagerClient flinkJobManagerClient;
    private final QualityExecutionClient qualityExecutionClient;
    private final LineageEvidenceClient lineageEvidenceClient;
    private final JdbcTemplate jdbc;

    @Autowired
    public TaskExecutionService(
            TaskService taskService,
            TaskInstanceService instanceService,
            TaskLogService logService,
            TaskDependencyService dependencyService,
            ExecutionClaimCoordinator claimCoordinator,
            SqlExecutionClient executionClient,
            NotificationClient notificationClient,
            SparkBatchExecutionService sparkExecutionService,
            FlinkSqlExecutionClient flinkExecutionClient,
            FlinkJobManagerClient flinkJobManagerClient,
            QualityExecutionClient qualityExecutionClient,
            LineageEvidenceClient lineageEvidenceClient,
            JdbcTemplate jdbc) {
        this.taskService = taskService;
        this.instanceService = instanceService;
        this.logService = logService;
        this.dependencyService = dependencyService;
        this.claimCoordinator = claimCoordinator;
        this.executionClient = executionClient;
        this.notificationClient = notificationClient;
        this.sparkExecutionService = sparkExecutionService;
        this.flinkExecutionClient = flinkExecutionClient;
        this.flinkJobManagerClient = flinkJobManagerClient;
        this.qualityExecutionClient = qualityExecutionClient;
        this.lineageEvidenceClient = lineageEvidenceClient;
        this.jdbc = jdbc;
    }

    /** Compatibility constructor retained for focused unit tests and embedders. */
    public TaskExecutionService(
            TaskService taskService, TaskInstanceService instanceService, TaskLogService logService,
            TaskDependencyService dependencyService, ExecutionClaimCoordinator claimCoordinator,
            SqlExecutionClient executionClient, NotificationClient notificationClient,
            SparkBatchExecutionService sparkExecutionService, QualityExecutionClient qualityExecutionClient,
            LineageEvidenceClient lineageEvidenceClient, JdbcTemplate jdbc) {
        this(taskService, instanceService, logService, dependencyService, claimCoordinator, executionClient,
                notificationClient, sparkExecutionService, null, null, qualityExecutionClient, lineageEvidenceClient, jdbc);
    }

    /**
     * 执行一个任务。
     *
     * @param taskId      任务主键
     * @param bizDate     业务日期 yyyy-MM-dd，对应目标表的分区键
     * @param triggerType manual / schedule / retry
     * @param triggeredBy 触发者用户名；定时触发时为 scheduler
     * @return 执行后的实例
     */
    public TaskInstance execute(Long taskId, String bizDate, String triggerType, String triggeredBy) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!"enabled".equals(task.getStatus())) {
            throw new IllegalStateException("任务已停用，无法执行");
        }

        int timeout = task.getTimeoutSeconds() == null ? 1800 : task.getTimeoutSeconds();
        ExecutionClaim claim = claimCoordinator.claim(
                task, bizDate, triggerType, triggeredBy, timeout);

        // 依赖未就绪时标记 skipped 而非 failed：这不是错误，只是还没到时候，
        // 上游补跑完成后重跑本任务即可。用 failed 会让告警噪声掩盖真正的故障。
        String unmetDependency = findUnmetDependency(taskId, bizDate, triggerType);
        if (unmetDependency != null) {
            TaskInstance skipped = claimCoordinator.skip(claim, unmetDependency);
            writeLog(skipped.getId(), claim.attemptId(), "warn",
                    "依赖未就绪，跳过执行：" + unmetDependency);
            return skipped;
        }

        String taskType = task.getTaskType() == null ? "sql" : task.getTaskType();
        return switch (taskType) {
            case "spark" -> sparkExecutionService.submit(task, claim);
            case "flink" -> runFlink(task, claim, timeout);
            case "quality" -> runQuality(task, claim);
            case "sql" -> runWithRetry(task, claim, timeout);
            default -> claimCoordinator.fail(claim, "不支持的任务类型", 0, 0);
        };
    }

    private TaskInstance runFlink(Task task, ExecutionClaim claim, int timeout) {
        long startedAt = System.currentTimeMillis();
        if (flinkExecutionClient == null) {
            return claimCoordinator.fail(claim, "Flink SQL 执行器未配置", 0, 0);
        }
        try {
            String sql = resolvePlaceholders(task.getTaskSql(), claim.instance().getBizDate());
            if ("streaming".equalsIgnoreCase(task.getFlinkMode())) {
                FlinkSqlExecutionClient.ExecutionResult handle = flinkExecutionClient.startStreaming(
                        task.getFlinkEndpoint(), sql, "attempt-" + claim.attemptId());
                String metadata = flinkResultMetadata(handle);
                TaskInstance running;
                try {
                    running = claimCoordinator.submittedFlink(claim, handle, metadata);
                } catch (RuntimeException persistenceError) {
                    // Do not orphan a remote stream when the local fenced write
                    // loses a race or the database is unavailable.
                    try {
                        flinkExecutionClient.cancelHandle(task.getFlinkEndpoint(),
                                handle.sessionHandle(), handle.operationHandle());
                    } catch (RuntimeException cancelError) {
                        log.error("Unable to cancel Flink stream after local persistence failure", cancelError);
                    }
                    throw persistenceError;
                }
                writeLog(claim.instance().getId(), claim.attemptId(), "info",
                        "Flink 持续任务已提交，session/operation 句柄已持久化");
                lineageEvidenceClient.sql(task, claim, sql);
                return running;
            }
            FlinkSqlExecutionClient.ExecutionResult result = flinkExecutionClient.execute(
                    task.getFlinkEndpoint(), sql, timeout, "attempt-" + claim.attemptId());
            long duration = System.currentTimeMillis() - startedAt;
            writeLog(claim.instance().getId(), claim.attemptId(), "info",
                    "Flink SQL 执行成功，操作句柄 " + result.operationHandle());
            TaskInstance succeeded = claimCoordinator.succeed(claim, result.affectedRows(),
                    result.durationMs() == null ? duration : result.durationMs(), duration,
                    flinkResultMetadata(result));
            lineageEvidenceClient.sql(task, claim, sql);
            return succeeded;
        } catch (FlinkSqlExecutionClient.FlinkExecutionException error) {
            long duration = System.currentTimeMillis() - startedAt;
            if (error.cancelled()) {
                writeLog(claim.instance().getId(), claim.attemptId(), "warn", "Flink SQL 已确认取消");
                return claimCoordinator.completeRemote(claim, new ExecutionAdapter.RemoteExecution(
                        null, "cancelled", null, null, null, null,
                        Map.of("engine", "flink-sql-gateway", "status", "cancelled"),
                        java.time.OffsetDateTime.now().toString()));
            }
            writeLog(claim.instance().getId(), claim.attemptId(), "error", error.getMessage());
            return claimCoordinator.fail(claim, error.getMessage(), duration, duration);
        }
    }

    public TaskInstance cancelFlink(long instanceId, long attemptId) {
        if (flinkExecutionClient == null) {
            throw new IllegalStateException("Flink SQL 当前没有可取消的远端操作");
        }
        TaskInstance current = instanceService.getById(instanceId);
        if (current == null) throw new IllegalArgumentException("运行实例不存在");
        Task task = taskService.getById(current.getTaskId());
        Map<String, Object> handle = jdbc.queryForMap(
                "SELECT remote_handle, flink_job_id FROM workflow.execution_attempt WHERE id=? AND instance_id=?",
                attemptId, instanceId);
        String jobId = text(handle.get("flink_job_id"));
        if (jobId != null) {
            requireJobManagerClient();
            flinkJobManagerClient.cancel(jobId);
            return claimCoordinator.requestCancelFlink(instanceId, attemptId);
        }
        String remoteHandle = handle.get("remote_handle") == null ? "" : String.valueOf(handle.get("remote_handle"));
        boolean canceled;
        if (remoteHandle.contains("/")) {
            String[] parts = remoteHandle.split("/", 2);
            canceled = flinkExecutionClient.cancelHandle(task == null ? null : task.getFlinkEndpoint(), parts[0], parts[1]);
        } else {
            canceled = flinkExecutionClient.cancel("attempt-" + attemptId);
        }
        if (!canceled) throw new IllegalStateException("Flink SQL 当前没有可取消的远端操作");
        return claimCoordinator.requestCancelFlink(instanceId, attemptId);
    }

    /** Read the remote Gateway state for a persisted streaming attempt. */
    public Map<String, Object> flinkStatus(long instanceId, long attemptId) {
        TaskInstance current = instanceService.getById(instanceId);
        if (current == null) throw new IllegalArgumentException("运行实例不存在");
        Task task = taskService.getById(current.getTaskId());
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT remote_handle, status AS attempt_status
                FROM workflow.execution_attempt
                WHERE id=? AND instance_id=?
                """, attemptId, instanceId);
        String remoteHandle = row.get("remote_handle") == null ? "" : String.valueOf(row.get("remote_handle"));
        if (!remoteHandle.contains("/")) throw new IllegalStateException("Flink 执行尚未持久化远端句柄");
        String[] parts = remoteHandle.split("/", 2);
        FlinkSqlExecutionClient.GatewayOperationStatus remote = flinkExecutionClient.operationStatus(
                task == null ? null : task.getFlinkEndpoint(), parts[0], parts[1]);
        return Map.of(
                "instanceStatus", current.getStatus(),
                "attemptStatus", String.valueOf(row.get("attempt_status")),
                "remoteStatus", remote.status(),
                "terminal", remote.terminal(),
                "sessionHandle", parts[0],
                "operationHandle", parts[1]);
    }

    /** Bind a verified JobManager Job ID to the persisted streaming attempt. */
    public Map<String, Object> bindFlinkJob(long instanceId, long attemptId, String jobId) {
        requireJobManagerClient();
        requireInstanceAttempt(instanceId, attemptId);
        FlinkJobManagerClient.JobStatus status = flinkJobManagerClient.status(jobId);
        int bound = jdbc.update("""
                UPDATE workflow.execution_attempt ea
                   SET flink_job_id=?, flink_lifecycle_state='running', flink_lifecycle_updated_at=now()
                 WHERE ea.id=? AND ea.instance_id=?
                   AND ea.status IN ('running','cancel_requested','unknown')
                   AND (ea.flink_job_id IS NULL OR ea.flink_job_id=?)
                   AND EXISTS (SELECT 1 FROM workflow.task_instance ti
                               WHERE ti.id=ea.instance_id AND ti.active_attempt_id=ea.id
                                 AND ti.fencing_token=ea.fencing_token)
                """, status.jobId(), attemptId, instanceId, status.jobId());
        if (bound != 1) throw new IllegalStateException("Flink Job 绑定与当前活动 attempt 冲突");
        return flinkJobStatus(instanceId, attemptId);
    }

    /** Query JobManager state and persist the latest completed checkpoint evidence. */
    public Map<String, Object> flinkJobStatus(long instanceId, long attemptId) {
        requireJobManagerClient();
        Map<String, Object> row = requireInstanceAttempt(instanceId, attemptId);
        String jobId = text(row.get("flink_job_id"));
        if (jobId == null) throw new IllegalStateException("Flink attempt 尚未绑定 Job ID");
        FlinkJobManagerClient.JobStatus status = flinkJobManagerClient.status(jobId);
        FlinkJobManagerClient.CheckpointSummary checkpoint = flinkJobManagerClient.checkpoints(jobId);
        jdbc.update("""
                UPDATE workflow.execution_attempt
                   SET flink_latest_checkpoint_id=COALESCE(?, flink_latest_checkpoint_id),
                       flink_lifecycle_updated_at=now()
                 WHERE id=? AND instance_id=?
                """, checkpoint.latestCompletedId(), attemptId, instanceId);
        Map<String, Object> result = new HashMap<>();
        result.put("jobId", jobId);
        result.put("state", status.state());
        result.put("name", status.name());
        result.put("latestCompletedCheckpointId", checkpoint.latestCompletedId());
        result.put("checkpointStatus", checkpoint.status());
        result.put("attemptId", attemptId);
        return result;
    }

    /** Trigger a savepoint and persist the asynchronous JobManager request handle. */
    public Map<String, Object> triggerFlinkSavepoint(long instanceId, long attemptId,
                                                      String targetDirectory, boolean cancelJob) {
        requireJobManagerClient();
        Map<String, Object> row = requireInstanceAttempt(instanceId, attemptId);
        String jobId = requiredJobId(row);
        FlinkJobManagerClient.TriggeredOperation operation = flinkJobManagerClient
                .triggerSavepoint(jobId, targetDirectory, cancelJob);
        jdbc.update("""
                UPDATE workflow.execution_attempt
                   SET flink_savepoint_request_id=?, flink_lifecycle_state=?, flink_lifecycle_updated_at=now()
                       , flink_lifecycle_intent=?
                 WHERE id=? AND instance_id=?
                """, operation.requestId(), cancelJob ? "stopping" : "running",
                cancelJob ? "stop" : null, attemptId, instanceId);
        return Map.of("jobId", jobId, "requestId", operation.requestId(), "cancelJob", cancelJob);
    }

    /** Poll a savepoint request and persist its completed location/failure evidence. */
    public Map<String, Object> flinkSavepointStatus(long instanceId, long attemptId, String requestId) {
        requireJobManagerClient();
        Map<String, Object> row = requireInstanceAttempt(instanceId, attemptId);
        String jobId = requiredJobId(row);
        FlinkJobManagerClient.SavepointStatus status = flinkJobManagerClient.savepointStatus(jobId, requestId);
        String remoteState = status.status() == null ? "" : status.status().toUpperCase();
        if (status.location() != null || Set.of("COMPLETED", "FAILED", "CANCELED", "CANCELLED").contains(remoteState)) {
            String intent = text(row.get("flink_lifecycle_intent"));
            String nextState = Set.of("FAILED", "CANCELED", "CANCELLED").contains(remoteState)
                    ? ("CANCELED".equals(remoteState) || "CANCELLED".equals(remoteState) ? "cancelled" : "failed")
                    : "COMPLETED".equalsIgnoreCase(status.status())
                    ? ("pause".equals(intent) ? "paused" : ("stop".equals(intent) || "upgrade".equals(intent)) ? "stopped" : text(row.get("flink_lifecycle_state")))
                    : text(row.get("flink_lifecycle_state"));
            jdbc.update("""
                    UPDATE workflow.execution_attempt
                   SET flink_savepoint_path=COALESCE(?, flink_savepoint_path),
                           flink_lifecycle_state=?,
                           flink_lifecycle_intent=CASE WHEN ? IN ('COMPLETED','FAILED','CANCELED','CANCELLED') THEN NULL ELSE flink_lifecycle_intent END,
                           lease_expires_at=CASE WHEN ? IN ('paused','stopped') THEN NULL ELSE lease_expires_at END,
                           flink_lifecycle_updated_at=now()
                     WHERE id=? AND instance_id=?
                    """, status.location(), nextState, status.status(), nextState, attemptId, instanceId);
            if ("paused".equals(nextState) || "stopped".equals(nextState)) {
                jdbc.update("""
                        UPDATE workflow.task_instance SET lease_expires_at=NULL, updated_at=now()
                        WHERE id=? AND active_attempt_id=?
                        """, instanceId, attemptId);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("jobId", jobId);
        result.put("requestId", status.requestId());
        result.put("status", status.status());
        result.put("location", status.location());
        result.put("failureCause", status.failureCause());
        return result;
    }

    /** Stop a job only through a JobManager stop-with-savepoint request. */
    public Map<String, Object> stopFlinkWithSavepoint(long instanceId, long attemptId,
                                                       String targetDirectory, boolean drain) {
        requireJobManagerClient();
        Map<String, Object> row = requireInstanceAttempt(instanceId, attemptId);
        String jobId = requiredJobId(row);
        FlinkJobManagerClient.TriggeredOperation operation = flinkJobManagerClient
                .stopWithSavepoint(jobId, targetDirectory, drain);
        jdbc.update("""
                UPDATE workflow.execution_attempt
                   SET flink_savepoint_request_id=?, flink_lifecycle_state='stopping',
                       flink_lifecycle_intent='stop',
                       flink_lifecycle_updated_at=now()
                 WHERE id=? AND instance_id=?
                """, operation.requestId(), attemptId, instanceId);
        return Map.of("jobId", jobId, "requestId", operation.requestId(), "drain", drain);
    }

    /**
     * Pause is implemented as stop-with-savepoint with a persisted pause intent.
     * The attempt becomes paused only after JobManager reports the savepoint
     * COMPLETED; a request/HTTP success alone never changes the terminal state.
     */
    public Map<String, Object> pauseFlink(long instanceId, long attemptId, String targetDirectory) {
        requireJobManagerClient();
        Map<String, Object> row = requireInstanceAttempt(instanceId, attemptId);
        String jobId = requiredJobId(row);
        if (!"running".equals(text(row.get("flink_lifecycle_state")))) {
            throw new IllegalStateException("只有 running 的 Flink Job 可以暂停");
        }
        if (jdbc.update("""
                UPDATE workflow.execution_attempt
                   SET flink_lifecycle_state='stopping', flink_lifecycle_intent='pause',
                       flink_lifecycle_updated_at=now()
                 WHERE id=? AND instance_id=? AND flink_lifecycle_state='running'
                """, attemptId, instanceId) != 1) {
            throw new IllegalStateException("Flink Job 状态已被其他生命周期操作占用");
        }
        try {
            FlinkJobManagerClient.TriggeredOperation operation = flinkJobManagerClient
                    .stopWithSavepoint(jobId, targetDirectory, false);
            jdbc.update("""
                    UPDATE workflow.execution_attempt
                       SET flink_savepoint_request_id=?, flink_lifecycle_updated_at=now()
                     WHERE id=? AND instance_id=?
                    """, operation.requestId(), attemptId, instanceId);
            return Map.of("jobId", jobId, "requestId", operation.requestId(), "intent", "pause");
        } catch (RuntimeException error) {
            jdbc.update("""
                    UPDATE workflow.execution_attempt
                       SET flink_lifecycle_state='running', flink_lifecycle_intent=NULL,
                           flink_lifecycle_updated_at=now()
                     WHERE id=? AND instance_id=? AND flink_lifecycle_intent='pause'
                    """, attemptId, instanceId);
            throw error;
        }
    }

    /**
     * Drain a running streaming job to a savepoint and retain the replacement
     * SQL until the same attempt resumes. The old definition is not mutated.
     */
    public Map<String, Object> upgradeFlink(long instanceId, long attemptId,
                                            String targetDirectory, String replacementSql) {
        requireJobManagerClient();
        if (flinkExecutionClient == null) throw new IllegalStateException("Flink SQL 执行器未配置");
        validateUpgradeSql(replacementSql);
        Map<String, Object> row = requireInstanceAttempt(instanceId, attemptId);
        String jobId = requiredJobId(row);
        if (!"running".equals(text(row.get("flink_lifecycle_state")))) {
            throw new IllegalStateException("只有 running 的 Flink Job 可以升级");
        }
        String digest = sha256(replacementSql);
        if (jdbc.update("""
                UPDATE workflow.execution_attempt
                   SET flink_lifecycle_state='upgrading', flink_lifecycle_intent='upgrade',
                       flink_upgrade_task_sql=?, flink_upgrade_definition_digest=?,
                       flink_lifecycle_updated_at=now()
                 WHERE id=? AND instance_id=? AND flink_lifecycle_state='running'
                """, replacementSql, digest, attemptId, instanceId) != 1) {
            throw new IllegalStateException("Flink Job 状态已被其他生命周期操作占用");
        }
        try {
            FlinkJobManagerClient.TriggeredOperation operation = flinkJobManagerClient
                    .stopWithSavepoint(jobId, targetDirectory, false);
            jdbc.update("""
                    UPDATE workflow.execution_attempt
                       SET flink_savepoint_request_id=?, flink_lifecycle_updated_at=now()
                     WHERE id=? AND instance_id=? AND flink_lifecycle_intent='upgrade'
                    """, operation.requestId(), attemptId, instanceId);
            return Map.of("jobId", jobId, "requestId", operation.requestId(),
                    "intent", "upgrade", "definitionDigest", digest);
        } catch (RuntimeException error) {
            jdbc.update("""
                    UPDATE workflow.execution_attempt
                       SET flink_lifecycle_state='running', flink_lifecycle_intent=NULL,
                           flink_upgrade_task_sql=NULL, flink_upgrade_definition_digest=NULL,
                           flink_lifecycle_updated_at=now()
                     WHERE id=? AND instance_id=? AND flink_lifecycle_intent='upgrade'
                    """, attemptId, instanceId);
            throw error;
        }
    }

    /** Resume a paused/stopped streaming attempt from its completed savepoint. */
    public Map<String, Object> resumeFlink(long instanceId, long attemptId) {
        if (flinkExecutionClient == null) throw new IllegalStateException("Flink SQL 执行器未配置");
        Map<String, Object> row = requireInstanceAttempt(instanceId, attemptId);
        String lifecycle = text(row.get("flink_lifecycle_state"));
        if (!"paused".equals(lifecycle) && !"stopped".equals(lifecycle)) {
            throw new IllegalStateException("只有 paused/stopped 的 Flink Job 可以恢复");
        }
        String savepoint = text(row.get("flink_savepoint_path"));
        if (savepoint == null) throw new IllegalStateException("Flink attempt 尚未保存可恢复的 savepoint");
        TaskInstance current = instanceService.getById(instanceId);
        if (current == null) throw new IllegalArgumentException("运行实例不存在");
        Task task = taskService.getById(current.getTaskId());
        if (task == null || !"flink".equals(task.getTaskType())
                || !"streaming".equalsIgnoreCase(task.getFlinkMode())) {
            throw new IllegalStateException("只有 streaming Flink 任务支持从 savepoint 恢复");
        }
        if (jdbc.update("""
                UPDATE workflow.execution_attempt
                   SET flink_lifecycle_state='resuming', flink_lifecycle_intent='resume',
                       flink_job_id=NULL, remote_handle=NULL,
                       flink_lifecycle_updated_at=now()
                 WHERE id=? AND instance_id=? AND flink_lifecycle_state IN ('paused','stopped')
                """, attemptId, instanceId) != 1) {
            throw new IllegalStateException("Flink Job 状态已被其他生命周期操作占用");
        }
        FlinkSqlExecutionClient.ExecutionResult handle = null;
        try {
            String upgradedSql = text(row.get("flink_upgrade_task_sql"));
            String sql = resolvePlaceholders(upgradedSql == null ? task.getTaskSql() : upgradedSql,
                    current.getBizDate());
            handle = flinkExecutionClient.startStreamingFromSavepoint(
                    task.getFlinkEndpoint(), sql, savepoint, "attempt-" + attemptId);
            String remoteHandle = handle.sessionHandle() + "/" + handle.operationHandle();
            int definitionVersion = claimCoordinator.persistFlinkResume(
                    instanceId, attemptId, task, remoteHandle, upgradedSql,
                    text(row.get("flink_upgrade_definition_digest")));
            Map<String, Object> result = new HashMap<>();
            result.put("attemptId", attemptId);
            result.put("sessionHandle", handle.sessionHandle());
            result.put("operationHandle", handle.operationHandle());
            result.put("savepoint", savepoint);
            result.put("state", "running");
            result.put("upgraded", upgradedSql != null);
            if (upgradedSql != null) {
                result.put("definitionDigest", text(row.get("flink_upgrade_definition_digest")));
                result.put("definitionVersion", definitionVersion);
            }
            return result;
        } catch (RuntimeException error) {
            if (handle != null) {
                try {
                    String submittedJob = flinkExecutionClient.submittedJobId(
                            task.getFlinkEndpoint(), handle.sessionHandle(), handle.operationHandle());
                    if (submittedJob != null && flinkJobManagerClient != null) {
                        flinkJobManagerClient.cancel(submittedJob);
                    } else {
                        flinkExecutionClient.cancelHandle(
                                task.getFlinkEndpoint(), handle.sessionHandle(), handle.operationHandle());
                    }
                } catch (RuntimeException cancelError) {
                    log.error("Unable to cancel Flink resume after persistence failure", cancelError);
                    try {
                        flinkExecutionClient.cancelHandle(
                                task.getFlinkEndpoint(), handle.sessionHandle(), handle.operationHandle());
                    } catch (RuntimeException fallbackError) {
                        log.error("Unable to cancel Flink resume Gateway operation", fallbackError);
                    }
                }
            }
            jdbc.update("""
                UPDATE workflow.execution_attempt
                   SET flink_lifecycle_state=?, flink_lifecycle_intent=NULL,
                       flink_job_id=?, remote_handle=?,
                       flink_lifecycle_updated_at=now()
                     WHERE id=? AND instance_id=? AND flink_lifecycle_intent='resume'
                    """, lifecycle, text(row.get("flink_job_id")), text(row.get("remote_handle")),
                    attemptId, instanceId);
            throw error;
        }
    }

    private Map<String, Object> requireInstanceAttempt(long instanceId, long attemptId) {
        TaskInstance current = instanceService.getById(instanceId);
        if (current == null) throw new IllegalArgumentException("运行实例不存在");
        try {
            return jdbc.queryForMap("""
                    SELECT flink_job_id, flink_lifecycle_state, flink_savepoint_request_id
                         , flink_lifecycle_intent, flink_savepoint_path
                         , flink_upgrade_task_sql, flink_upgrade_definition_digest
                         , remote_handle
                      FROM workflow.execution_attempt
                     WHERE id=? AND instance_id=?
                    """, attemptId, instanceId);
        } catch (org.springframework.dao.EmptyResultDataAccessException error) {
            throw new IllegalArgumentException("Flink 执行 attempt 不存在");
        }
    }

    private String requiredJobId(Map<String, Object> row) {
        String jobId = text(row.get("flink_job_id"));
        if (jobId == null) throw new IllegalStateException("Flink attempt 尚未绑定 Job ID");
        return jobId;
    }

    private void requireJobManagerClient() {
        if (flinkJobManagerClient == null) throw new IllegalStateException("Flink JobManager 执行器未配置");
    }

    private static String lifecycleState(String state) {
        if (state == null) return "unknown";
        return switch (state.toUpperCase()) {
            case "RUNNING", "RESTARTING", "CREATED", "INITIALIZING" -> "running";
            case "CANCELED" -> "cancelled";
            case "FINISHED" -> "stopped";
            case "FAILED" -> "failed";
            default -> "unknown";
        };
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static void validateUpgradeSql(String sql) {
        FlinkSqlScript.statements(sql, true);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String flinkResultMetadata(FlinkSqlExecutionClient.ExecutionResult result) {
        return "{\"engine\":\"flink-sql-gateway\",\"sessionHandle\":\""
                + jsonEscape(result.sessionHandle()) + "\",\"operationHandle\":\""
                + jsonEscape(result.operationHandle()) + "\"}";
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private TaskInstance runQuality(Task task, ExecutionClaim claim) {
        long startedAt = System.currentTimeMillis();
        try {
            QualityExecutionClient.QualityResult result = qualityExecutionClient.execute(
                    task.getQualityRuleId(), claim.instance().getTriggeredBy());
            long duration = System.currentTimeMillis() - startedAt;
            if ("passed".equals(result.status())) {
                writeLog(claim.instance().getId(), claim.attemptId(), "info",
                        "质量门禁通过，违规数 " + result.violationCount());
                return claimCoordinator.succeed(claim,
                        result.violationCount() == null ? 0 : result.violationCount(), duration, duration);
            }
            String message = "质量门禁未通过：" + (result.message() == null ? result.status() : result.message());
            writeLog(claim.instance().getId(), claim.attemptId(), "error", message);
            return claimCoordinator.fail(claim, message, duration, duration);
        } catch (RuntimeException error) {
            long duration = System.currentTimeMillis() - startedAt;
            writeLog(claim.instance().getId(), claim.attemptId(), "error", error.getMessage());
            return claimCoordinator.fail(claim, error.getMessage(), duration, duration);
        }
    }

    /** Each retry receives its own immutable attempt row and fencing token. */
    private TaskInstance runWithRetry(Task task, ExecutionClaim initialClaim, int timeout) {
        int maxRetries = task.getMaxRetries() == null ? 0 : task.getMaxRetries();
        long executionStartedAt = System.currentTimeMillis();
        ExecutionClaim claim = initialClaim;
        SqlExecutionException lastError = null;

        for (int retryIndex = 0; retryIndex <= maxRetries; retryIndex++) {
            long attemptStartedAt = System.currentTimeMillis();
            try {
                writeLog(claim.instance().getId(), claim.attemptId(), "info",
                        "开始第 " + claim.attemptNo() + " 次执行，业务日期 "
                                + claim.instance().getBizDate());

                // 业务日期以参数形式注入 SQL：任务 SQL 里写 ${bizDate} 占位符，
                // 由这里替换成实际日期，避免每天手改 SQL。
                String sql = resolvePlaceholders(task.getTaskSql(), claim.instance().getBizDate());
                SqlExecutionClient.ExecutionResult result =
                        executionClient.execute(task.getDatasourceId(), sql, timeout);

                long attemptDuration = System.currentTimeMillis() - attemptStartedAt;
                TaskInstance succeeded = claimCoordinator.succeed(
                        claim, result.affectedRows(), attemptDuration,
                        System.currentTimeMillis() - executionStartedAt);
                writeLog(succeeded.getId(), claim.attemptId(), "info",
                        "执行成功，耗时 " + attemptDuration + "ms");
                lineageEvidenceClient.sql(task, claim, sql);
                return succeeded;
            } catch (SqlExecutionException error) {
                lastError = error;
                long attemptDuration = System.currentTimeMillis() - attemptStartedAt;
                TaskInstance failed = claimCoordinator.fail(
                        claim, error.getMessage(), attemptDuration,
                        System.currentTimeMillis() - executionStartedAt);
                writeLog(failed.getId(), claim.attemptId(), "error",
                        "第 " + claim.attemptNo() + " 次执行失败：" + error.getMessage());
                log.warn("Task {} attempt {} failed: {}",
                        task.getId(), claim.attemptNo(), error.getMessage());

                if (retryIndex == maxRetries) {
                    notificationClient.notifyAdmin(task.getId(), failed.getBizDate(), "critical",
                            "任务执行失败：" + task.getName(),
                            "业务日期 " + failed.getBizDate() + "，重试耗尽后仍失败："
                                    + truncate(lastError.getMessage()));
                    return failed;
                }
                claim = claimCoordinator.claim(
                        task, failed.getBizDate(), "retry", failed.getTriggeredBy(), timeout);
            }
        }
        throw new IllegalStateException("任务执行循环异常结束", lastError);
    }

    /**
     * 替换 SQL 中的业务日期占位符。
     *
     * <p>只支持 {@code ${bizDate}} 一个占位符，且值来自服务端按 yyyy-MM-dd 校验过的
     * biz_date，不接受用户自由文本，因此不构成注入面。
     */
    String resolvePlaceholders(String sql, String bizDate) {
        return sql == null ? null : sql.replace("${bizDate}", bizDate);
    }

    /**
     * 检查上游依赖在同一业务日期是否都已成功。
     *
     * @return 未就绪的上游任务描述；全部就绪时返回 null
     */
    String findUnmetDependency(Long taskId, String bizDate) {
        return findUnmetDependency(taskId, bizDate, null);
    }

    String findUnmetDependency(Long taskId, String bizDate, String triggerType) {
        List<TaskDependency> dependencies = dependencyService.list(
                new LambdaQueryWrapper<TaskDependency>().eq(TaskDependency::getTaskId, taskId));
        if (dependencies.isEmpty()) {
            return null;
        }

        for (TaskDependency dependency : dependencies) {
            TaskInstance upstream = instanceService.getOne(
                    new LambdaQueryWrapper<TaskInstance>()
                            .eq(TaskInstance::getTaskId, dependency.getUpstreamTaskId())
                            .eq(TaskInstance::getBizDate, bizDate));
            if (upstream == null || !"success".equals(upstream.getStatus())) {
                Task upstreamTask = taskService.getById(dependency.getUpstreamTaskId());
                if ("pipeline".equals(triggerType) && activeQualityWaiver(upstreamTask, bizDate)) {
                    continue;
                }
                String name = upstreamTask == null
                        ? "任务 " + dependency.getUpstreamTaskId()
                        : upstreamTask.getName();
                String state = upstream == null ? "尚未执行" : "状态为 " + upstream.getStatus();
                return "上游「" + name + "」在 " + bizDate + " " + state;
            }
        }
        return null;
    }

    private boolean activeQualityWaiver(Task upstreamTask, String bizDate) {
        if (upstreamTask == null || !"quality".equals(upstreamTask.getTaskType())) return false;
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM workflow.quality_waiver
                WHERE quality_task_id=? AND biz_date=? AND expires_at>now()
                """, Integer.class, upstreamTask.getId(), bizDate);
        return count != null && count > 0;
    }

    /** 日志写入失败不应让任务本身失败，因此单独捕获。 */
    private void writeLog(Long instanceId, Long attemptId, String level, String message) {
        try {
            TaskLog entry = new TaskLog();
            entry.setInstanceId(instanceId);
            entry.setAttemptId(attemptId);
            entry.setLevel(level);
            entry.setMessage(message);
            logService.save(entry);
        } catch (RuntimeException error) {
            log.warn("Failed to persist task log for instance {}", instanceId, error);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) + "…" : text;
    }
}
