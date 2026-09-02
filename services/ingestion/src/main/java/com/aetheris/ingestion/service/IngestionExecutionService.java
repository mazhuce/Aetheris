package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.entity.IngestionTask;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

@Service
public class IngestionExecutionService {

    private final IngestionTaskService taskService;
    private final IngestionRunService runService;
    private final MetadataIngestionClient client;
    private final NotificationClient notificationClient;

    public IngestionExecutionService(
            IngestionTaskService taskService,
            IngestionRunService runService,
            MetadataIngestionClient client,
            NotificationClient notificationClient) {
        this.taskService = taskService;
        this.runService = runService;
        this.client = client;
        this.notificationClient = notificationClient;
    }

    public IngestionRun execute(Long taskId, String username) {
        IngestionTask task = taskService.getById(taskId);
        if (task == null) throw new IllegalArgumentException("接入任务不存在");
        validate(task);

        IngestionRun run = new IngestionRun();
        run.setTaskId(taskId);
        run.setStatus("running");
        run.setTriggeredBy(StringUtils.hasText(username) ? username : "unknown");
        run.setStartedAt(OffsetDateTime.now());
        try {
            runService.save(run);
        } catch (DataIntegrityViolationException error) {
            throw new IllegalStateException("该任务已有运行中的实例");
        }

        try {
            MetadataIngestionClient.TransferResult result = client.execute(task);
            run.setStatus("success");
            run.setRowsRead(result.rowsRead());
            run.setRowsWritten(result.rowsWritten());
            run.setDurationMs(result.durationMs());
            run.setFinishedAt(OffsetDateTime.now());
            runService.updateById(run);
            updateTask(task, "success", result.rowsWritten(), null);
            return run;
        } catch (RuntimeException error) {
            String message = safeMessage(error);
            run.setStatus("failed");
            run.setErrorMessage(message);
            run.setFinishedAt(OffsetDateTime.now());
            runService.updateById(run);
            updateTask(task, "failed", null, message);
            notificationClient.notifyAdmin(taskId, "critical",
                    "数据接入失败：" + task.getName(), message);
            throw new MetadataIngestionClient.IngestionExecutionException(message);
        }
    }

    private void validate(IngestionTask task) {
        if (!"enabled".equals(task.getStatus())) {
            throw new IllegalStateException("接入任务已停用");
        }
        if (task.getSourceDatasourceId() == null || task.getTargetDatasourceId() == null
                || !StringUtils.hasText(task.getSourceSql()) || !StringUtils.hasText(task.getTargetTable())) {
            throw new IllegalArgumentException("请完整配置来源数据源、目标数据源、来源 SQL 和目标表");
        }
    }

    private void updateTask(IngestionTask task, String status, Long rows, String error) {
        task.setLastRunStatus(status);
        task.setLastRunAt(OffsetDateTime.now());
        task.setLastRunRows(rows);
        task.setLastError(error);
        task.setUpdatedAt(OffsetDateTime.now());
        taskService.updateById(task);
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return StringUtils.hasText(message) ? message.substring(0, Math.min(message.length(), 512)) : "数据接入执行失败";
    }
}
