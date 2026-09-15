package com.aetheris.workflow.controller;

import com.aetheris.workflow.dto.TaskRunRequest;
import com.aetheris.workflow.dto.TaskView;
import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskDependency;
import com.aetheris.workflow.entity.ExecutionAttempt;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.entity.TaskLog;
import com.aetheris.workflow.service.SqlExecutionClient;
import com.aetheris.workflow.service.ExecutionAttemptService;
import com.aetheris.workflow.service.ExecutionResolutionService;
import com.aetheris.workflow.service.ManagedSparkCatalogService;
import com.aetheris.workflow.service.SparkBatchExecutionService;
import com.aetheris.workflow.service.TaskDependencyService;
import com.aetheris.workflow.service.TaskExecutionService;
import com.aetheris.workflow.service.TaskInstanceService;
import com.aetheris.workflow.service.TaskLogService;
import com.aetheris.workflow.service.TaskSchedulerService;
import com.aetheris.workflow.service.TaskService;
import com.aetheris.workflow.service.WorkflowAuthorizationService;
import com.aetheris.workflow.security.GatewayIdentityInterceptor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** 任务编排接口：任务定义、手动触发、运行实例、日志与依赖。 */
@RestController
@RequestMapping("/api/workflow/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);
    private static final DateTimeFormatter BIZ_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TaskService taskService;
    private final TaskInstanceService instanceService;
    private final TaskLogService logService;
    private final ExecutionAttemptService attemptService;
    private final ExecutionResolutionService resolutionService;
    private final TaskDependencyService dependencyService;
    private final TaskExecutionService executionService;
    private final TaskSchedulerService schedulerService;
    private final SqlExecutionClient executionClient;
    private final ManagedSparkCatalogService sparkCatalog;
    private final SparkBatchExecutionService sparkExecutionService;
    private final WorkflowAuthorizationService authorizationService;

    public TaskController(
            TaskService taskService,
            TaskInstanceService instanceService,
            TaskLogService logService,
            ExecutionAttemptService attemptService,
            ExecutionResolutionService resolutionService,
            TaskDependencyService dependencyService,
            TaskExecutionService executionService,
            TaskSchedulerService schedulerService,
            SqlExecutionClient executionClient,
            ManagedSparkCatalogService sparkCatalog,
            SparkBatchExecutionService sparkExecutionService,
            WorkflowAuthorizationService authorizationService) {
        this.taskService = taskService;
        this.instanceService = instanceService;
        this.logService = logService;
        this.attemptService = attemptService;
        this.resolutionService = resolutionService;
        this.dependencyService = dependencyService;
        this.executionService = executionService;
        this.schedulerService = schedulerService;
        this.executionClient = executionClient;
        this.sparkCatalog = sparkCatalog;
        this.sparkExecutionService = sparkExecutionService;
        this.authorizationService = authorizationService;
    }

    /** 任务列表，附带调度状态与最近一次运行结果，避免界面为每行再发请求。 */
    @GetMapping
    public List<TaskView> list() {
        List<Task> tasks = taskService.list(
                new LambdaQueryWrapper<Task>().orderByDesc(Task::getUpdatedAt));
        List<TaskView> views = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            views.add(toView(task));
        }
        return views;
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskView> getById(@PathVariable Long id) {
        Task task = taskService.getById(id);
        return task == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(toView(task));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody Task task,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        String rejection = validateDefinition(task);
        if (rejection != null) {
            return ResponseEntity.badRequest().body(Map.of("message", rejection));
        }

        task.setId(null);
        if (!StringUtils.hasText(task.getTaskType())) task.setTaskType("sql");
        applyDefaults(task, username);
        taskService.save(task);
        schedulerService.sync(task);
        return ResponseEntity.ok(toView(task));
    }

    /**
     * 保存前校验任务定义。
     *
     * <p>cron 与 SQL 都在入库前校验，因为两者的失败都只在执行时才暴露：
     * cron 不合法会导致任务保存成功但从不触发，SQL 不被白名单接受会导致每次运行都失败。
     * 实测中用户创建的任务正是卡在后者——保存成功、点运行才看到拒绝原因。
     *
     * @return 不通过时返回原因；通过时返回 null
     */
    private String validateDefinition(Task task) {
        try {
            TaskSchedulerService.validateCron(task.getCronExpression());
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
        String type = StringUtils.hasText(task.getTaskType()) ? task.getTaskType() : "sql";
        return switch (type) {
            case "sql" -> {
                if (task.getDatasourceId() == null || !StringUtils.hasText(task.getTaskSql())) {
                    yield "SQL 任务必须选择数据源并填写加工 SQL";
                }
                if (task.getJobDefinitionId() != null || task.getQualityRuleId() != null) {
                    yield "SQL 任务不能引用 Spark 作业或质量规则";
                }
                yield executionClient.validateWrite(task.getTaskSql());
            }
            case "spark" -> {
                if (task.getJobDefinitionId() == null || task.getDatasourceId() != null
                        || StringUtils.hasText(task.getTaskSql()) || task.getQualityRuleId() != null) {
                    yield "Spark 任务必须且只能引用一个已发布受管作业";
                }
                try {
                    sparkCatalog.runnableJob(task.getJobDefinitionId());
                    yield null;
                } catch (RuntimeException error) {
                    yield error.getMessage();
                }
            }
            case "quality" -> {
                if (task.getQualityRuleId() == null || task.getDatasourceId() != null
                        || StringUtils.hasText(task.getTaskSql()) || task.getJobDefinitionId() != null) {
                    yield "质量节点必须且只能引用一个质量规则";
                }
                yield null;
            }
            default -> "不支持的任务类型";
        };
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody Task task) {
        Task existing = taskService.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (!StringUtils.hasText(task.getTaskType())) task.setTaskType(existing.getTaskType());
        String rejection = validateDefinition(task);
        if (rejection != null) {
            return ResponseEntity.badRequest().body(Map.of("message", rejection));
        }

        task.setId(id);
        task.setUpdatedAt(OffsetDateTime.now());
        taskService.updateById(task);

        Task updated = taskService.getById(id);
        // 改完 cron 或启停状态后必须重新同步，否则会出现「界面显示已停用但仍在跑」。
        schedulerService.sync(updated);
        return ResponseEntity.ok(toView(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id) {
        if (taskService.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        // 先摘调度再删定义：反过来的话，删除后到摘除之间的窗口里触发会拿不到任务而报错。
        schedulerService.unschedule(id);
        taskService.removeById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 手动触发。
     *
     * <p>同步执行而非入队：数仓任务的反馈需要立即可见，且这里已有 Quartz 承担定时场景。
     * 长任务的等待由前端超时控制，执行状态在实例表里可查。
     */
    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(
            @PathVariable Long id,
            @RequestBody(required = false) TaskRunRequest request,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        String bizDate = request == null || request.bizDate() == null || request.bizDate().isBlank()
                // 默认取昨天，与定时触发口径一致：数仓通常加工前一天的完整数据。
                ? LocalDate.now().minusDays(1).format(BIZ_DATE)
                : request.bizDate();

        try {
            TaskInstance instance = executionService.execute(
                    id, bizDate, "manual", username == null ? "unknown" : username);
            return ResponseEntity.ok(instance);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            // 任务已停用或同日期已有实例在跑：属于冲突而非服务故障。
            return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
        } catch (RuntimeException error) {
            log.warn("Manual run failed for task {}", id, error);
            return ResponseEntity.status(500).body(Map.of("message", "任务执行失败，请查看运行日志"));
        }
    }

    /** 运行实例列表，按业务日期倒序。 */
    @GetMapping("/{id}/instances")
    public List<TaskInstance> instances(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int limit) {
        return instanceService.list(
                new LambdaQueryWrapper<TaskInstance>()
                        .eq(TaskInstance::getTaskId, id)
                        .orderByDesc(TaskInstance::getBizDate)
                        .last("LIMIT " + Math.clamp(limit, 1, 200)));
    }

    /** 全局运行实例列表（任务监控页用），按开始时间倒序，可按状态过滤。 */
    @GetMapping("/instances")
    public List<TaskInstance> allInstances(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return instanceService.list(
                new LambdaQueryWrapper<TaskInstance>()
                        .eq(StringUtils.hasText(status), TaskInstance::getStatus, status)
                        .orderByDesc(TaskInstance::getStartedAt)
                        .last("LIMIT " + Math.clamp(limit, 1, 200)));
    }

    /** 某次运行的日志。 */
    @GetMapping("/instances/{instanceId}/logs")
    public ResponseEntity<?> logs(@PathVariable Long instanceId,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorizationService.canReadInstance(username, instanceId)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权查看该实例日志"));
        }
        return ResponseEntity.ok(logService.list(
                new LambdaQueryWrapper<TaskLog>()
                        .eq(TaskLog::getInstanceId, instanceId)
                        .orderByAsc(TaskLog::getId)));
    }

    /** Physical attempts are append-only audit records for retries and recovery. */
    @GetMapping("/instances/{instanceId}/attempts")
    public ResponseEntity<?> attempts(@PathVariable Long instanceId,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorizationService.canReadInstance(username, instanceId)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权查看该实例执行记录"));
        }
        return ResponseEntity.ok(attemptService.list(
                new LambdaQueryWrapper<ExecutionAttempt>()
                        .eq(ExecutionAttempt::getInstanceId, instanceId)
                        .orderByDesc(ExecutionAttempt::getAttemptNo)));
    }

    /** Returns the immutable manual resolution record, when this instance needed one. */
    @GetMapping("/instances/{instanceId}/resolution")
    public ResponseEntity<?> resolution(@PathVariable long instanceId,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorizationService.canReadInstance(username, instanceId)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权查看该实例核对记录"));
        }
        return resolutionService.find(instanceId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Resolves a handle-less unknown Spark result from externally verified evidence.
     * Gateway authorization already protects mutations; the explicit manager check is
     * defense in depth for direct service calls and future routing changes.
     */
    @PostMapping("/instances/{instanceId}/resolve")
    public ResponseEntity<?> resolve(
            @PathVariable long instanceId,
            @RequestBody ResolutionRequest request,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorizationService.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "只有管理员可以认领未知执行结果"));
        }
        try {
            return ResponseEntity.ok(resolutionService.resolve(
                    instanceId, request.status(), request.reason(), request.evidenceRef(), username));
        } catch (ExecutionResolutionService.ResolutionNotFoundException error) {
            return ResponseEntity.status(404).body(Map.of("message", error.getMessage()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
        }
    }

    public record ResolutionRequest(String status, String reason, String evidenceRef) { }

    @GetMapping("/instances/{instanceId}/spark-logs")
    public ResponseEntity<?> sparkLogs(@PathVariable long instanceId,
                                       @RequestParam(defaultValue = "0") long offset,
                                       @RequestParam(defaultValue = "32768") int limit,
                                       @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorizationService.canReadInstance(username, instanceId)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权查看该实例日志"));
        }
        try {
            return ResponseEntity.ok(sparkExecutionService.remoteLogs(instanceId, offset, limit));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
    }

    @PostMapping("/instances/{instanceId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable long instanceId,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorizationService.canReadInstance(username, instanceId)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权操作该实例"));
        }
        try {
            return ResponseEntity.ok(sparkExecutionService.cancel(instanceId));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
        }
    }

    @GetMapping("/{id}/dependencies")
    public List<TaskDependency> dependencies(@PathVariable Long id) {
        return dependencyService.list(
                new LambdaQueryWrapper<TaskDependency>().eq(TaskDependency::getTaskId, id));
    }

    @PostMapping("/{id}/dependencies")
    public ResponseEntity<?> addDependency(
            @PathVariable Long id,
            @RequestParam Long upstreamTaskId) {
        if (taskService.getById(id) == null || taskService.getById(upstreamTaskId) == null) {
            return ResponseEntity.notFound().build();
        }
        if (id.equals(upstreamTaskId)) {
            return ResponseEntity.badRequest().body(Map.of("message", "任务不能依赖自身"));
        }
        // 环会让相关任务永远等不到上游成功，形成静默死锁，因此在写入前检测。
        if (createsCycle(id, upstreamTaskId)) {
            return ResponseEntity.badRequest().body(Map.of("message", "该依赖会形成环形依赖"));
        }

        TaskDependency dependency = new TaskDependency();
        dependency.setTaskId(id);
        dependency.setUpstreamTaskId(upstreamTaskId);
        try {
            dependencyService.save(dependency);
        } catch (RuntimeException error) {
            // 唯一约束命中说明这条边已存在，视为幂等成功而非错误。
            log.debug("Dependency already exists: {} <- {}", id, upstreamTaskId);
        }
        return ResponseEntity.ok(dependency);
    }

    @DeleteMapping("/{id}/dependencies/{upstreamTaskId}")
    public ResponseEntity<Void> removeDependency(
            @PathVariable Long id,
            @PathVariable Long upstreamTaskId) {
        dependencyService.remove(
                new LambdaQueryWrapper<TaskDependency>()
                        .eq(TaskDependency::getTaskId, id)
                        .eq(TaskDependency::getUpstreamTaskId, upstreamTaskId));
        return ResponseEntity.noContent().build();
    }

    /**
     * 从 upstreamTaskId 出发沿依赖边向上遍历，若能回到 taskId 说明会形成环。
     *
     * <p>用迭代而非递归，避免依赖链过长时栈溢出；visited 集合同时防止在已有环上死循环。
     */
    private boolean createsCycle(Long taskId, Long upstreamTaskId) {
        List<Long> frontier = new ArrayList<>();
        frontier.add(upstreamTaskId);
        java.util.Set<Long> visited = new java.util.HashSet<>();

        while (!frontier.isEmpty()) {
            Long current = frontier.remove(frontier.size() - 1);
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(taskId)) {
                return true;
            }
            dependencyService.list(
                            new LambdaQueryWrapper<TaskDependency>()
                                    .eq(TaskDependency::getTaskId, current))
                    .forEach(edge -> frontier.add(edge.getUpstreamTaskId()));
        }
        return false;
    }

    private void applyDefaults(Task task, String username) {
        if (task.getStatus() == null) {
            task.setStatus("enabled");
        }
        if (task.getTimeoutSeconds() == null) {
            task.setTimeoutSeconds(1800);
        }
        if (task.getMaxRetries() == null) {
            task.setMaxRetries(0);
        }
        if (task.getOwner() == null && username != null) {
            task.setOwner(username);
        }
    }

    private TaskView toView(Task task) {
        Date nextFire = schedulerService.nextFireTime(task.getId());
        TaskInstance last = instanceService.getOne(
                new LambdaQueryWrapper<TaskInstance>()
                        .eq(TaskInstance::getTaskId, task.getId())
                        .orderByDesc(TaskInstance::getBizDate)
                        .last("LIMIT 1"));
        long upstreamCount = dependencyService.count(
                new LambdaQueryWrapper<TaskDependency>().eq(TaskDependency::getTaskId, task.getId()));

        return new TaskView(
                task,
                nextFire == null ? null : nextFire.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                last == null ? null : last.getStatus(),
                last == null ? null : last.getBizDate(),
                last == null ? null : last.getFinishedAt(),
                (int) upstreamCount);
    }
}
