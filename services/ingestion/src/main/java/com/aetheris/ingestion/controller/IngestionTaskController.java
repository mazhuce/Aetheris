package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.service.IngestionExecutionService;
import com.aetheris.ingestion.service.IngestionRunService;
import com.aetheris.ingestion.service.IngestionSchedulerService;
import com.aetheris.ingestion.service.IngestionTaskService;
import com.aetheris.ingestion.security.GatewayIdentityInterceptor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingestion/tasks")
public class IngestionTaskController {

    private final IngestionTaskService service;
    private final IngestionRunService runService;
    private final IngestionExecutionService executionService;
    private final IngestionSchedulerService schedulerService;

    public IngestionTaskController(
            IngestionTaskService service,
            IngestionRunService runService,
            IngestionExecutionService executionService,
            IngestionSchedulerService schedulerService) {
        this.service = service;
        this.runService = runService;
        this.executionService = executionService;
        this.schedulerService = schedulerService;
    }

    @GetMapping
    public List<IngestionTask> list() { return service.list(); }

    @GetMapping("/{id}")
    public ResponseEntity<IngestionTask> getById(@PathVariable Long id) {
        IngestionTask task = service.getById(id);
        return task != null ? ResponseEntity.ok(task) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public IngestionTask create(@Valid @RequestBody IngestionTask task) {
        task.setId(null);
        applyDefaults(task);
        IngestionSchedulerService.validateCron(task.getScheduleCron());
        service.save(task);
        schedulerService.sync(task);
        return task;
    }

    @PutMapping("/{id}")
    public ResponseEntity<IngestionTask> update(@PathVariable Long id, @Valid @RequestBody IngestionTask task) {
        task.setId(id);
        applyDefaults(task);
        IngestionSchedulerService.validateCron(task.getScheduleCron());
        boolean updated = service.updateById(task);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        IngestionTask saved = service.getById(id);
        schedulerService.sync(saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = service.removeById(id);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        // 先删数据再取消调度：调度残留最多导致一次「任务不存在」的失败日志，反向则可能漏删。
        schedulerService.unschedule(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(
            @PathVariable Long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        try {
            return ResponseEntity.ok(executionService.execute(id, username));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
        } catch (RuntimeException error) {
            return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
        }
    }

    @GetMapping("/{id}/runs")
    public List<IngestionRun> runs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int limit) {
        return runService.list(new LambdaQueryWrapper<IngestionRun>()
                .eq(IngestionRun::getTaskId, id)
                .orderByDesc(IngestionRun::getStartedAt)
                .last("LIMIT " + Math.clamp(limit, 1, 200)));
    }

    private void applyDefaults(IngestionTask task) {
        if (task.getSourceType() == null || task.getSourceType().isBlank()) task.setSourceType("jdbc");
        if (task.getStatus() == null || task.getStatus().isBlank()) task.setStatus("enabled");
        if (task.getWriteMode() == null || task.getWriteMode().isBlank()) task.setWriteMode("append");
        if (task.getBatchSize() == null) task.setBatchSize(500);
    }
}
