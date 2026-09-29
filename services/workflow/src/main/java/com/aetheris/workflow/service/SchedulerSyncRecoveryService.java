package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Quartz 同步失败的最小补偿闭环。
 *
 * <p>任务定义和 Quartz 使用不同的持久化边界，不能假设一次 HTTP 请求能同时提交两者。
 * 本服务把「任务已写入、调度未同步」记录到 workflow.scheduler_sync_outbox，
 * 由短周期重试恢复；记录失败不会覆盖原始调度异常，避免把真实故障伪装成成功。
 */
@Service
public class SchedulerSyncRecoveryService {

    private static final int MAX_ERROR_LENGTH = 1024;
    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate jdbc;
    private final TaskService taskService;
    private final TaskSchedulerService schedulerService;
    private final int maxAttempts;
    private final int retryDelaySeconds;

    public SchedulerSyncRecoveryService(
            JdbcTemplate jdbc,
            TaskService taskService,
            TaskSchedulerService schedulerService,
            @Value("${aetheris.scheduler-recovery.max-attempts:8}") int maxAttempts,
            @Value("${aetheris.scheduler-recovery.retry-delay-seconds:30}") int retryDelaySeconds) {
        this.jdbc = jdbc;
        this.taskService = taskService;
        this.schedulerService = schedulerService;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelaySeconds = Math.max(1, retryDelaySeconds);
    }

    /** 记录失败但不替换调用方正在返回的原始异常。 */
    public void recordFailure(Long taskId, String operation, RuntimeException error) {
        if (taskId == null || (!"sync".equals(operation) && !"unschedule".equals(operation))) {
            return;
        }
        String message = error == null ? "未知调度同步错误" : truncate(error.getMessage());
        try {
            jdbc.update("""
                    INSERT INTO workflow.scheduler_sync_outbox
                        (task_id, operation, status, attempts, next_attempt_at, last_error, updated_at)
                    VALUES (?, ?, 'pending', 0, now(), ?, now())
                    ON CONFLICT (task_id, operation) WHERE status IN ('pending', 'processing')
                    DO UPDATE SET status = 'pending', next_attempt_at = now(),
                                  last_error = EXCLUDED.last_error, updated_at = now()
                    """, taskId, operation, message);
        } catch (DataAccessException persistError) {
            // 原始请求仍应失败；这里只补充日志，不能让记录故障覆盖调度故障。
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .error("Unable to persist scheduler sync recovery for task {}", taskId, persistError);
        }
    }

    /** 定期领取并重试到期补偿项；Quartz 本身负责多实例调度，这里用状态更新做轻量抢占。 */
    @Scheduled(
            fixedDelayString = "${aetheris.scheduler-recovery.interval-ms:5000}",
            initialDelayString = "${aetheris.scheduler-recovery.initial-delay-ms:10000}")
    public void retryPending() {
        // 进程在调度调用中止后可能留下 processing，超过 10 分钟才释放，避免重复同步。
        jdbc.update("""
                UPDATE workflow.scheduler_sync_outbox
                   SET status = 'pending', updated_at = now()
                 WHERE status = 'processing'
                   AND updated_at < now() - interval '10 minutes'
                """);

        List<RecoveryEntry> entries = jdbc.query("""
                SELECT id, task_id, operation
                  FROM workflow.scheduler_sync_outbox
                 WHERE status = 'pending'
                   AND next_attempt_at <= now()
                 ORDER BY id
                 LIMIT ?
                """, (rs, rowNum) -> new RecoveryEntry(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("operation")), BATCH_SIZE);

        for (RecoveryEntry entry : entries) {
            int claimed = jdbc.update("""
                    UPDATE workflow.scheduler_sync_outbox
                       SET status = 'processing', attempts = attempts + 1, updated_at = now()
                     WHERE id = ? AND status = 'pending'
                    """, entry.id());
            if (claimed != 1) {
                continue;
            }
            try {
                reconcile(entry);
                jdbc.update("""
                        UPDATE workflow.scheduler_sync_outbox
                           SET status = 'succeeded', last_error = NULL, updated_at = now()
                         WHERE id = ?
                        """, entry.id());
            } catch (RuntimeException error) {
                jdbc.update("""
                        UPDATE workflow.scheduler_sync_outbox
                           SET status = CASE WHEN attempts >= ? THEN 'failed' ELSE 'pending' END,
                               next_attempt_at = CASE WHEN attempts >= ? THEN next_attempt_at
                                                       ELSE now() + (? * interval '1 second') END,
                               last_error = ?, updated_at = now()
                         WHERE id = ?
                        """, maxAttempts, maxAttempts, retryDelaySeconds,
                        truncate(error.getMessage()), entry.id());
            }
        }
    }

    private void reconcile(RecoveryEntry entry) {
        if ("unschedule".equals(entry.operation())) {
            schedulerService.unschedule(entry.taskId());
            return;
        }
        Task task = taskService.getById(entry.taskId());
        // 任务已删除时，sync 补偿的安全结果是确保没有孤儿 Quartz Job。
        if (task == null) {
            schedulerService.unschedule(entry.taskId());
        } else {
            schedulerService.sync(task);
        }
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "未知调度同步错误";
        }
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }

    private record RecoveryEntry(long id, long taskId, String operation) { }
}
