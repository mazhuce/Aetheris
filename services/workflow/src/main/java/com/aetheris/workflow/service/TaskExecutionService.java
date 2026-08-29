package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskDependency;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.entity.TaskLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 任务执行器。
 *
 * <p>幂等策略：{@code (task_id, biz_date)} 唯一，同一业务日期复用同一实例行。重跑时把该行
 * 重置为 running 并累加 attempt，而不是新插一行——这样实例列表不会因反复重跑膨胀，
 * 也从库层排除了「同一业务日期被两个实例并发写入目标表」。
 *
 * <p>数据幂等则依赖加工语句本身用 {@code INSERT OVERWRITE ... PARTITION}：覆盖目标分区，
 * 因此重跑不会产生重复数据，也不需要先删表（{@code DROP}/{@code TRUNCATE} 被写入守卫拒绝）。
 *
 * <p>事务边界：状态流转各自独立提交（{@code REQUIRES_NEW}），不把长时间的 SQL 执行包在
 * 事务里。否则一个跑半小时的任务会持有数据库连接与事务半小时，且执行中的状态对外不可见，
 * 界面上会一直显示 pending。
 */
@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final TaskService taskService;
    private final TaskInstanceService instanceService;
    private final TaskLogService logService;
    private final TaskDependencyService dependencyService;
    private final SqlExecutionClient executionClient;

    public TaskExecutionService(
            TaskService taskService,
            TaskInstanceService instanceService,
            TaskLogService logService,
            TaskDependencyService dependencyService,
            SqlExecutionClient executionClient) {
        this.taskService = taskService;
        this.instanceService = instanceService;
        this.logService = logService;
        this.dependencyService = dependencyService;
        this.executionClient = executionClient;
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

        TaskInstance instance = claimInstance(task, bizDate, triggerType, triggeredBy);

        // 依赖未就绪时标记 skipped 而非 failed：这不是错误，只是还没到时候，
        // 上游补跑完成后重跑本任务即可。用 failed 会让告警噪声掩盖真正的故障。
        String unmetDependency = findUnmetDependency(taskId, bizDate);
        if (unmetDependency != null) {
            return finishSkipped(instance, unmetDependency);
        }

        return runWithRetry(task, instance);
    }

    /**
     * 抢占实例行并置为 running。
     *
     * <p>独立事务提交，让界面能立刻看到 running 状态；同时利用唯一索引判断是否已有实例在跑，
     * 避免定时触发与人工触发同时执行同一任务同一日期。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected TaskInstance claimInstance(Task task, String bizDate, String triggerType, String triggeredBy) {
        TaskInstance existing = instanceService.getOne(
                new LambdaQueryWrapper<TaskInstance>()
                        .eq(TaskInstance::getTaskId, task.getId())
                        .eq(TaskInstance::getBizDate, bizDate));

        if (existing != null && "running".equals(existing.getStatus())) {
            throw new IllegalStateException("该任务在此业务日期已有实例正在执行");
        }

        TaskInstance instance = existing == null ? new TaskInstance() : existing;
        instance.setTaskId(task.getId());
        instance.setBizDate(bizDate);
        instance.setStatus("running");
        instance.setTriggerType(triggerType);
        instance.setAttempt(existing == null ? 1 : existing.getAttempt() + 1);
        instance.setStartedAt(OffsetDateTime.now());
        instance.setFinishedAt(null);
        instance.setDurationMs(null);
        instance.setAffectedRows(null);
        instance.setErrorMessage(null);
        instance.setTriggeredBy(triggeredBy);
        instance.setUpdatedAt(OffsetDateTime.now());

        if (existing == null) {
            instanceService.save(instance);
        } else {
            instanceService.updateById(instance);
        }
        return instance;
    }

    /** 按 max_retries 重试；每次尝试都记日志，便于事后判断是偶发还是稳定失败。 */
    private TaskInstance runWithRetry(Task task, TaskInstance instance) {
        int maxRetries = task.getMaxRetries() == null ? 0 : task.getMaxRetries();
        int timeout = task.getTimeoutSeconds() == null ? 1800 : task.getTimeoutSeconds();
        long startedAt = System.currentTimeMillis();
        SqlExecutionException lastError = null;

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                writeLog(instance.getId(), "info", attempt == 1
                        ? "开始执行，业务日期 " + instance.getBizDate()
                        : "第 " + attempt + " 次尝试");

                // 业务日期以参数形式注入 SQL：任务 SQL 里写 ${bizDate} 占位符，
                // 由这里替换成实际日期，避免每天手改 SQL。
                String sql = resolvePlaceholders(task.getTaskSql(), instance.getBizDate());
                SqlExecutionClient.ExecutionResult result =
                        executionClient.execute(task.getDatasourceId(), sql, timeout);

                return finishSuccess(instance, result, System.currentTimeMillis() - startedAt);
            } catch (SqlExecutionException error) {
                lastError = error;
                writeLog(instance.getId(), "error",
                        "第 " + attempt + " 次尝试失败：" + error.getMessage());
                log.warn("Task {} attempt {} failed: {}", task.getId(), attempt, error.getMessage());
            }
        }

        return finishFailed(instance, lastError, System.currentTimeMillis() - startedAt);
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
                String name = upstreamTask == null
                        ? "任务 " + dependency.getUpstreamTaskId()
                        : upstreamTask.getName();
                String state = upstream == null ? "尚未执行" : "状态为 " + upstream.getStatus();
                return "上游「" + name + "」在 " + bizDate + " " + state;
            }
        }
        return null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected TaskInstance finishSuccess(
            TaskInstance instance, SqlExecutionClient.ExecutionResult result, long durationMs) {
        instance.setStatus("success");
        instance.setFinishedAt(OffsetDateTime.now());
        instance.setDurationMs(durationMs);
        // Hive 驱动不返回行数时为 -1，保留原值并在界面上显示为「—」，
        // 不要伪造成 0，否则会被误读为「没写入任何数据」。
        instance.setAffectedRows(result.affectedRows());
        instance.setErrorMessage(null);
        instance.setUpdatedAt(OffsetDateTime.now());
        instanceService.updateById(instance);
        writeLog(instance.getId(), "info", "执行成功，耗时 " + durationMs + "ms");
        return instance;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected TaskInstance finishFailed(
            TaskInstance instance, SqlExecutionException error, long durationMs) {
        instance.setStatus("failed");
        instance.setFinishedAt(OffsetDateTime.now());
        instance.setDurationMs(durationMs);
        instance.setErrorMessage(truncate(error == null ? "未知错误" : error.getMessage()));
        instance.setUpdatedAt(OffsetDateTime.now());
        instanceService.updateById(instance);
        writeLog(instance.getId(), "error", "执行失败，耗时 " + durationMs + "ms");
        return instance;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected TaskInstance finishSkipped(TaskInstance instance, String reason) {
        instance.setStatus("skipped");
        instance.setFinishedAt(OffsetDateTime.now());
        instance.setDurationMs(0L);
        instance.setErrorMessage(truncate(reason));
        instance.setUpdatedAt(OffsetDateTime.now());
        instanceService.updateById(instance);
        writeLog(instance.getId(), "warn", "依赖未就绪，跳过执行：" + reason);
        return instance;
    }

    /** 日志写入失败不应让任务本身失败，因此单独捕获。 */
    private void writeLog(Long instanceId, String level, String message) {
        try {
            TaskLog entry = new TaskLog();
            entry.setInstanceId(instanceId);
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
