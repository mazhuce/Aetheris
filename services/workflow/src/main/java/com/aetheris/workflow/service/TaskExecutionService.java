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
import java.util.List;

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
    private final QualityExecutionClient qualityExecutionClient;
    private final LineageEvidenceClient lineageEvidenceClient;
    private final JdbcTemplate jdbc;

    public TaskExecutionService(
            TaskService taskService,
            TaskInstanceService instanceService,
            TaskLogService logService,
            TaskDependencyService dependencyService,
            ExecutionClaimCoordinator claimCoordinator,
            SqlExecutionClient executionClient,
            NotificationClient notificationClient,
            SparkBatchExecutionService sparkExecutionService,
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
        this.qualityExecutionClient = qualityExecutionClient;
        this.lineageEvidenceClient = lineageEvidenceClient;
        this.jdbc = jdbc;
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
            case "quality" -> runQuality(task, claim);
            case "sql" -> runWithRetry(task, claim, timeout);
            default -> claimCoordinator.fail(claim, "不支持的任务类型", 0, 0);
        };
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
