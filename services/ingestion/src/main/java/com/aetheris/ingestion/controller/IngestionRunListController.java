package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.service.IngestionRunService;
import com.aetheris.ingestion.service.IngestionTaskService;
import com.aetheris.security.protocol.pagination.VisibleListCursor;
import com.aetheris.security.protocol.pagination.VisibleListPage;
import com.aetheris.security.protocol.pagination.VisibleListScanner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 数据同步视图：跨任务的全局运行记录。
 *
 * <p>与 {@code /api/ingestion/tasks/{id}/runs}（单任务视角）互补，独立端点而非改动
 * 既有 controller，供「数据同步」页面按任务名、状态检索全部同步执行历史。
 */
@RestController
@RequestMapping("/api/ingestion/runs")
public class IngestionRunListController {
    private com.aetheris.ingestion.security.ResourceAuthorization authorization = new com.aetheris.ingestion.security.ResourceAuthorization();
    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthorization(com.aetheris.ingestion.security.ResourceAuthorization authorization) { this.authorization = authorization; }

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
        if (RequestContextHolder.getRequestAttributes() == null) {
            List<IngestionRun> runs = authorization.filter("ingestion_task", runService.list(new LambdaQueryWrapper<IngestionRun>()
                    .eq(taskId != null, IngestionRun::getTaskId, taskId)
                    .eq(status != null && !status.isBlank(), IngestionRun::getStatus, status)
                    .orderByDesc(IngestionRun::getId).last("LIMIT 200")), IngestionRun::getTaskId);
            return toMaps(runs);
        }
        VisibleListPage<Map<String, Object>> page = page(taskId, status, 200, null);
        if (page.hasMore()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "运行记录超过兼容列表上限，请使用分页接口");
        }
        return page.items();
    }

    @GetMapping("/page")
    public VisibleListPage<Map<String, Object>> page(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor) {
        var proof = authorization.currentUser().proof();
        String hash = hash("ingestion-runs", taskId, status, size, proof.uid(), proof.username(), proof.accessVersion());
        VisibleListPage<IngestionRun> raw = VisibleListScanner.page(cursor, size, hash, upper(taskId, status),
                (upper, before, limit) -> runService.list(new LambdaQueryWrapper<IngestionRun>()
                        .eq(taskId != null, IngestionRun::getTaskId, taskId)
                        .eq(status != null && !status.isBlank(), IngestionRun::getStatus, status)
                        .le(IngestionRun::getId, upper)
                        .lt(before != null, IngestionRun::getId, before)
                        .orderByDesc(IngestionRun::getId)
                        .last("LIMIT " + limit)),
                IngestionRun::getId,
                rows -> authorization.filter("ingestion_task", rows, IngestionRun::getTaskId)
                        .stream().map(IngestionRun::getId).collect(Collectors.toSet()));
        return new VisibleListPage<>(toMaps(raw.items()), raw.nextCursor(), raw.hasMore());
    }

    private List<Map<String, Object>> toMaps(List<IngestionRun> runs) {
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
            item.put("failureCode", run.getFailureCode());
            item.put("requestKey", run.getRequestKey());
            item.put("replayOfRunId", run.getReplayOfRunId());
            item.put("triggeredBy", run.getTriggeredBy());
            item.put("startedAt", run.getStartedAt());
            item.put("finishedAt", run.getFinishedAt());
            return item;
        }).toList();
    }

    private long upper(Long taskId, String status) {
        return runService.list(new LambdaQueryWrapper<IngestionRun>()
                .select(IngestionRun::getId)
                .eq(taskId != null, IngestionRun::getTaskId, taskId)
                .eq(status != null && !status.isBlank(), IngestionRun::getStatus, status)
                .orderByDesc(IngestionRun::getId).last("LIMIT 1"))
                .stream().map(IngestionRun::getId).filter(java.util.Objects::nonNull).findFirst().orElse(0L);
    }

    private static String hash(Object... parts) {
        String canonical = Arrays.stream(parts).map(value -> {
            String text = value == null ? "<null>" : String.valueOf(value);
            return text.length() + ":" + text;
        }).collect(Collectors.joining("|"));
        return VisibleListCursor.hash(canonical);
    }
}
