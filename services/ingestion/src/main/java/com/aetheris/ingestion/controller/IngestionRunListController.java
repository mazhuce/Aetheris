package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.service.IngestionRunService;
import com.aetheris.ingestion.service.IngestionTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据同步视图：跨任务的全局运行记录。
 *
 * <p>与 {@code /api/ingestion/tasks/{id}/runs}（单任务视角）互补，独立端点而非改动
 * 既有 controller，供「数据同步」页面按任务名、状态检索全部同步执行历史。
 */
@RestController
@RequestMapping("/api/ingestion/runs")
public class IngestionRunListController {

    private final IngestionRunService runService;
    private final IngestionTaskService taskService;

    public IngestionRunListController(IngestionRunService runService, IngestionTaskService taskService) {
        this.runService = runService;
        this.taskService = taskService;
    }

    /**
     * 全局运行记录：按开始时间倒序，最多 200 条。
     *
     * @param taskId 只看指定任务（可选）
     * @param status 按状态过滤：running / success / failed
     */
    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String status) {
        List<IngestionRun> runs = runService.list(new LambdaQueryWrapper<IngestionRun>()
                .eq(taskId != null, IngestionRun::getTaskId, taskId)
                .eq(status != null && !status.isBlank(), IngestionRun::getStatus, status)
                .orderByDesc(IngestionRun::getStartedAt)
                .last("LIMIT 200"));
        // 任务可能已被删除：用任务快照字段兜底，避免页面出现纯数字 taskId 无法辨认。
        Map<Long, String> taskNames = new HashMap<>();
        for (IngestionRun run : runs) {
            taskNames.computeIfAbsent(run.getTaskId(), id -> {
                IngestionTask task = taskService.getById(id);
                return task != null ? task.getName() : "任务 #" + id + "（已删除）";
            });
        }
        return runs.stream().map(run -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", run.getId());
            item.put("taskId", run.getTaskId());
            item.put("taskName", taskNames.getOrDefault(run.getTaskId(), "未知任务"));
            item.put("status", run.getStatus());
            item.put("rowsRead", run.getRowsRead());
            item.put("rowsWritten", run.getRowsWritten());
            item.put("durationMs", run.getDurationMs());
            item.put("errorMessage", run.getErrorMessage());
            item.put("triggeredBy", run.getTriggeredBy());
            item.put("startedAt", run.getStartedAt());
            item.put("finishedAt", run.getFinishedAt());
            return item;
        }).toList();
    }
}
