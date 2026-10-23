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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingestion/tasks")
public class IngestionTaskController {
    private com.aetheris.ingestion.security.ResourceAuthorization authorization = new com.aetheris.ingestion.security.ResourceAuthorization();
    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthorization(com.aetheris.ingestion.security.ResourceAuthorization authorization) { this.authorization = authorization; }

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
    public List<IngestionTask> list() { return authorization.filter("ingestion_task", service.list(), IngestionTask::getId); }

    @GetMapping("/{id}")
    public ResponseEntity<IngestionTask> getById(@PathVariable Long id) {
        authorization.require("ingestion_task", id, "view");
        IngestionTask task = service.getById(id);
        return task != null ? ResponseEntity.ok(task) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public IngestionTask create(@Valid @RequestBody IngestionTask task) {
        authorization.require("ingestion_task", null, "create");
        authorization.requireManage();
        task.setId(null);
        applyDefaults(task);
        IngestionSchedulerService.validateCron(task.getScheduleCron());
        if("enabled".equals(task.getStatus()) && task.getScheduleCron()!=null && !task.getScheduleCron().isBlank())authorization.require("ingestion_task",null,"execute");
        service.save(task);
        schedulerService.sync(task);
        return task;
    }

    @PutMapping("/{id}")
    public ResponseEntity<IngestionTask> update(@PathVariable Long id, @Valid @RequestBody IngestionTask task) {
        authorization.require("ingestion_task", id, "update");
        IngestionTask existing=service.getById(id);
        authorization.protectDefinition(task,existing,java.util.Set.of("name","description","status","scheduleCron"));
        if(existing!=null) {
            if(task.getStatus()==null || task.getStatus().isBlank())task.setStatus(existing.getStatus());
            if ((task.getScheduleCron()!=null && !java.util.Objects.equals(task.getScheduleCron(),existing.getScheduleCron()))
                    || ("disabled".equals(existing.getStatus()) && "enabled".equals(task.getStatus()))) {
                authorization.require("ingestion_task",id,"execute");
            }
        }
        task.setId(id);
        applyDefaults(task);
        IngestionSchedulerService.validateCron(task.getScheduleCron());
        authorization.retainEditable(task,java.util.Set.of("name","description","status","scheduleCron"));
        boolean updated = service.updateById(task);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        IngestionTask saved = service.getById(id);
        schedulerService.sync(saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        authorization.require("ingestion_task", id, "delete");
        String unresolved = "仍有执行中或待对账运行，禁止删除接入任务";
        if (runService.count(new LambdaQueryWrapper<IngestionRun>().eq(IngestionRun::getTaskId,id)
                .in(IngestionRun::getStatus,java.util.List.of("running","unknown"))) > 0) {
            return ResponseEntity.status(409).body(Map.of("message",unresolved));
        }
        boolean removed;
        try { removed = service.removeById(id); }
        catch (org.springframework.dao.DataAccessException failure) {
            // The database trigger is the final guard against a run starting
            // between the friendly precheck and deletion.
            for (Throwable cause=failure; cause!=null; cause=cause.getCause()) {
                if(cause instanceof java.sql.SQLException sql && "55000".equals(sql.getSQLState()))
                    return ResponseEntity.status(409).body(Map.of("message",unresolved));
            }
            throw failure;
        }
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
            @RequestAttribute("com.aetheris.security.servlet.TrustedUser.authenticated") com.aetheris.security.servlet.TrustedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        authorization.require("ingestion_task", id, "execute");
        try {
            return ResponseEntity.ok(executionService.executeManual(id, user, requestKey,null));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
        } catch (RuntimeException error) {
            return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
        }
    }

    /** Retired Java bridge. HTTP execution requires a verified typed subject. */
    @Deprecated public ResponseEntity<?> run(Long id,String ignoredUsername) {
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"需要已验签执行主体");
    }

    @GetMapping("/{id}/runs")
    public List<IngestionRun> runs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int limit) {
        authorization.require("ingestion_task", id, "view");
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
