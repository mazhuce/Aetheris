package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes the dependency closure of one target as a mixed SQL/Spark/quality DAG. */
@Service
public class PipelineExecutionService {
    private com.aetheris.workflow.fixedexecution.PipelineExecutionStore authority;
    @org.springframework.beans.factory.annotation.Autowired
    public void installPipelineAuthority(com.aetheris.workflow.fixedexecution.PipelineExecutionStore authority) { this.authority=java.util.Objects.requireNonNull(authority); }
    private WorkflowAuthorizationService authorization;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthorization(WorkflowAuthorizationService authorization) { this.authorization = authorization; }
    private static final int MAX_NODE_MESSAGE_LENGTH = 512;
    private static final int MAX_RUN_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final TaskService taskService;
    private final TaskInstanceService instanceService;
    private final TaskExecutionService executionService;
    private final TaskExecutor taskExecutor;
    private final long dispatchLeaseSeconds;
    private final long leaseGraceSeconds;

    public PipelineExecutionService(JdbcTemplate jdbc, TaskService taskService,
                                    TaskInstanceService instanceService,
                                    TaskExecutionService executionService,
                                    TaskExecutor taskExecutor,
                                    @Value("${aetheris.pipeline.dispatch-lease-seconds:120}") long dispatchLeaseSeconds,
                                    @Value("${aetheris.pipeline.lease-grace-seconds:120}") long leaseGraceSeconds) {
        if (dispatchLeaseSeconds < 30 || leaseGraceSeconds < 1) {
            throw new IllegalStateException("流水线租约配置无效");
        }
        this.jdbc = jdbc;
        this.taskService = taskService;
        this.instanceService = instanceService;
        this.executionService = executionService;
        this.taskExecutor = taskExecutor;
        this.dispatchLeaseSeconds = dispatchLeaseSeconds;
        this.leaseGraceSeconds = leaseGraceSeconds;
    }

    @Transactional
    public Map<String, Object> start(long targetTaskId, String bizDate, String username) {
        if (taskService.getById(targetTaskId) == null) throw new IllegalArgumentException("目标任务不存在");
        validateBizDate(bizDate);
        List<Long> order = topologicalOrder(targetTaskId);
        validateRunnableTasks(order);
        return createRun(targetTaskId, bizDate, username, order, order, null, null);
    }

    /**
     * Resumes a failed or confirmed-cancelled pipeline from one terminal node.
     * Unknown outcomes are deliberately excluded because repeating a remote Spark
     * submission before reconciliation could duplicate side effects.
     */
    @Transactional
    public Map<String, Object> retry(long sourceRunId, long resumeTaskId, String username) {
        Map<String, Object> source = run(sourceRunId);
        String sourceStatus = String.valueOf(source.get("status"));
        List<Map<String, Object>> nodes = jdbc.queryForList("""
                SELECT task_id,status FROM workflow.pipeline_node_run
                WHERE pipeline_run_id=? AND task_id=?
                """, sourceRunId, resumeTaskId);
        if (nodes.isEmpty()) throw new IllegalArgumentException("续跑节点不属于来源流水线");
        String nodeStatus = String.valueOf(nodes.getFirst().get("status"));
        validateRetrySource(sourceStatus, nodeStatus);

        long targetTaskId = ((Number) source.get("target_task_id")).longValue();
        String bizDate = String.valueOf(source.get("biz_date"));
        List<Long> fullOrder = topologicalOrder(targetTaskId);
        validateRunnableTasks(fullOrder);
        List<Long> resumeOrder = resumeOrder(fullOrder, downstreamOf(resumeTaskId), resumeTaskId);
        return createRun(targetTaskId, bizDate, username, fullOrder, resumeOrder, sourceRunId, resumeTaskId);
    }

    private Map<String, Object> createRun(long targetTaskId, String bizDate, String username,
                                           List<Long> fullOrder,List<Long> order, Long retryOfRunId, Long resumeFromTaskId) {
        // Authorize the exact immutable node snapshot persisted and dispatched below.
        // Dependency changes after this point cannot add unvalidated execution nodes.
        order = List.copyOf(order);
        if (authorization == null || !authorization.canExecuteTasks(username, fullOrder)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "流水线包含无权执行的任务");
        }
        if(authority==null)throw new IllegalStateException("可信流水线存储未配置");
        Long runId;
        try {
            runId=authority.create(targetTaskId,bizDate,authorization.pipelineActor(username),fullOrder,order,retryOfRunId,resumeFromTaskId,dispatchLeaseSeconds);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("该目标在此业务日期已有运行中的流水线", duplicate);
        }
        dispatchAfterCommit(runId, bizDate, username, order);
        return run(runId);
    }

    private void dispatchAfterCommit(long runId, String bizDate, String username, List<Long> order) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    taskExecutor.execute(() -> executeRun(runId, bizDate, username, order));
                } catch (RuntimeException rejected) {
                    authority.rejectQueued(runId);
                }
            }
        });
    }

    public List<Map<String, Object>> list(int limit, String username, boolean includeAll) {
        int boundedLimit = Math.clamp(limit, 1, 100);
        if (!includeAll) {
            return jdbc.queryForList("""
                    SELECT run.*, task.name target_task_name
                    FROM workflow.pipeline_run run JOIN workflow.task task ON task.id=run.target_task_id
                    WHERE run.triggered_by=? OR task.owner=?
                    ORDER BY run.id DESC LIMIT ?
                    """, username, username, boundedLimit).stream().map(PipelineExecutionService::publicRun).toList();
        }
        return jdbc.queryForList("""
                SELECT run.*, task.name target_task_name
                FROM workflow.pipeline_run run JOIN workflow.task task ON task.id=run.target_task_id
                ORDER BY run.id DESC LIMIT ?
                """, boundedLimit).stream().map(PipelineExecutionService::publicRun).toList();
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> run = run(id);
        run.put("nodes", jdbc.queryForList("""
                SELECT node.*, task.name task_name, task.task_type
                FROM workflow.pipeline_node_run node
                JOIN workflow.task task ON task.id=node.task_id
                WHERE node.pipeline_run_id=? ORDER BY node.sequence_no
                """, id).stream().map(PipelineExecutionService::publicNode).toList());
        return run;
    }

    @Transactional
    public Map<String, Object> createWaiver(long qualityTaskId, String bizDate, String reason,
                                            OffsetDateTime expiresAt, String username) {
        Task task = taskService.getById(qualityTaskId);
        if (task == null || !"quality".equals(task.getTaskType())) {
            throw new IllegalArgumentException("豁免对象必须是质量任务");
        }
        validateBizDate(bizDate);
        if (reason == null || reason.isBlank() || reason.length() > 512) {
            throw new IllegalArgumentException("必须填写 1-512 字的豁免原因");
        }
        if (expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now())
                || expiresAt.isAfter(OffsetDateTime.now().plusDays(31))) {
            throw new IllegalArgumentException("豁免有效期必须在未来 31 天内");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO workflow.quality_waiver
                    (quality_task_id,biz_date,reason,approved_by,expires_at)
                VALUES (?,?,?,?,?)
                ON CONFLICT (quality_task_id,biz_date) DO UPDATE SET
                    reason=EXCLUDED.reason, approved_by=EXCLUDED.approved_by,
                    expires_at=EXCLUDED.expires_at, created_at=now()
                RETURNING id
                """, Long.class, qualityTaskId, bizDate, reason.trim(), username, expiresAt);
        return jdbc.queryForMap("SELECT * FROM workflow.quality_waiver WHERE id=?", id);
    }

    void executeRun(long runId, String bizDate, String username, List<Long> order) {
        if(authority==null)throw new IllegalStateException("可信流水线存储未配置");
        var dispatch=authority.begin(runId);if(dispatch==null)return;
        bizDate=dispatch.bizDate();username=dispatch.username();order=dispatch.order();
        Long currentTaskId = null;
        boolean dispatched=false;
        try {
            for (Long taskId : order) {
                currentTaskId = taskId;
                dispatched=false;
                Task task = taskService.getById(taskId);
                if (task == null) throw new IllegalStateException("流水线节点任务不存在：" + taskId);
                validateRunnableTask(task);
                var claim=authority.prepare(dispatch,taskId);
                dispatched=true;
                TaskInstance instance=executionService.executePipeline(claim);
                instance = awaitTerminal(task, instance);
                String nodeStatus = switch (instance.getStatus() == null ? "unknown" : instance.getStatus()) {
                    case "success" -> "succeeded";
                    case "cancelled" -> "cancelled";
                    case "failed", "timed_out", "skipped" -> "failed";
                    default -> "unknown";
                };
                Long waiverId = null;
                if (QualityGateEvidence.confirmedFailure(jdbc, task, instance)) {
                    waiverId = activeWaiver(taskId, bizDate);
                    if (waiverId != null) nodeStatus = "waived";
                }
                if(!authority.record(dispatch,taskId,claim,instance,nodeStatus,waiverId))throw new IllegalStateException("节点结果绑定已变化");
                if ("unknown".equals(nodeStatus)) {
                    authority.finish(dispatch,"unknown",taskId,"节点结果待核对，禁止自动重放");
                    return;
                }
                if ("cancelled".equals(nodeStatus)) {
                    authority.finish(dispatch,"cancelled",taskId,"节点已确认取消");
                    return;
                }
                if (!"succeeded".equals(nodeStatus) && !"waived".equals(nodeStatus)) {
                    authority.finish(dispatch,"failed",taskId,"节点执行失败");return;
                }
            }
            authority.finish(dispatch,"succeeded",null,null);
        } catch (RuntimeException error) {
            // Once a physical submit was possible, even a local persistence error remains uncertain.
            authority.finish(dispatch,dispatched?"unknown":"failed",currentTaskId,
                    dispatched?"节点执行调用或完成状态待核对，禁止自动重放":"节点批准版本、依赖或执行条件已变化，未提交该节点");
        }
    }

    /** Ensures a failed run has no node left looking active or waiting forever. */
    void failRun(long runId, Long currentTaskId, String failure) {
        String runMessage = bounded(failure, MAX_RUN_ERROR_LENGTH);
        String nodeMessage = bounded(failure, MAX_NODE_MESSAGE_LENGTH);
        if (currentTaskId != null) {
            jdbc.update("""
                    UPDATE workflow.pipeline_node_run
                    SET status='failed',finished_at=COALESCE(finished_at,now()),
                        message=COALESCE(message,?)
                    WHERE pipeline_run_id=? AND task_id=? AND status IN ('queued','running')
                    """, nodeMessage, runId, currentTaskId);
        }
        jdbc.update("""
                UPDATE workflow.pipeline_node_run
                SET status='skipped',finished_at=now(),message='上游节点失败，未执行'
                WHERE pipeline_run_id=? AND status='queued'
                """, runId);
        jdbc.update("""
                UPDATE workflow.pipeline_run
                SET status='failed',error_message=?,finished_at=now(),heartbeat_at=now(),
                    lease_expires_at=NULL,updated_at=now()
                WHERE id=? AND status IN ('queued','running')
                """, runMessage, runId);
    }

    /** Keeps an uncertain remote outcome distinct from an ordinary business failure. */
    void suspendRun(long runId, String reason) {
        jdbc.update("""
                UPDATE workflow.pipeline_node_run
                SET status='skipped',finished_at=now(),message='上游结果待核对，节点未执行'
                WHERE pipeline_run_id=? AND status='queued'
                """, runId);
        jdbc.update("""
                UPDATE workflow.pipeline_run
                SET status='unknown',error_message=?,finished_at=now(),heartbeat_at=now(),
                    lease_expires_at=NULL,updated_at=now()
                WHERE id=? AND status='running'
                """, bounded(reason, MAX_RUN_ERROR_LENGTH), runId);
    }

    void cancelRun(long runId, String reason) {
        jdbc.update("""
                UPDATE workflow.pipeline_node_run
                SET status='skipped',finished_at=now(),message='流水线已取消，节点未执行'
                WHERE pipeline_run_id=? AND status='queued'
                """, runId);
        jdbc.update("""
                UPDATE workflow.pipeline_run
                SET status='cancelled',error_message=?,finished_at=now(),heartbeat_at=now(),
                    lease_expires_at=NULL,updated_at=now()
                WHERE id=? AND status='running'
                """, bounded(reason, MAX_RUN_ERROR_LENGTH), runId);
    }

    private boolean refreshLease(long runId, long leaseSeconds) {
        return jdbc.update("""
                UPDATE workflow.pipeline_run
                SET heartbeat_at=now(),lease_expires_at=now() + (? * interval '1 second'),updated_at=now()
                WHERE id=? AND status='running'
                """, leaseSeconds, runId) == 1;
    }

    /** Releases stale active rows after a worker crash without guessing node outcomes. */
    @Scheduled(fixedDelayString = "${aetheris.pipeline.reconcile-interval-ms:30000}")
    @Transactional
    public void reconcileStaleRuns() {
        List<Long> stale = jdbc.queryForList("""
                UPDATE workflow.pipeline_run
                SET status='unknown',error_message='流水线执行心跳超时，需要核对后重跑',
                    finished_at=now(),lease_expires_at=NULL,updated_at=now()
                WHERE status IN ('queued','running')
                  AND lease_expires_at IS NOT NULL AND lease_expires_at < now()
                RETURNING id
                """, Long.class);
        for (Long runId : stale) {
            jdbc.update("""
                    UPDATE workflow.pipeline_node_run
                    SET status='unknown',finished_at=COALESCE(finished_at,now()),
                        message=COALESCE(message,'执行心跳超时，节点结果待核对')
                    WHERE pipeline_run_id=? AND status='running'
                    """, runId);
            jdbc.update("""
                    UPDATE workflow.pipeline_node_run
                    SET status='skipped',finished_at=now(),
                        message=COALESCE(message,'流水线已中断，节点未执行')
                    WHERE pipeline_run_id=? AND status='queued'
                    """, runId);
        }
    }

    /** Continues a suspended pipeline after the Spark reconciler proves its terminal result. */
    @Scheduled(fixedDelayString = "${aetheris.pipeline.result-reconcile-interval-ms:5000}")
    @Transactional
    public void reconcileResolvedNodes() {
        if(authority==null)throw new IllegalStateException("可信流水线存储未配置");
        for(long id:authority.recover())dispatchAfterCommit(id,null,null,List.of());
    }

    static String bounded(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    static void validateRetrySource(String runStatus, String nodeStatus) {
        if ("unknown".equals(runStatus) || "unknown".equals(nodeStatus)) {
            throw new IllegalStateException("结果待核对的流水线禁止重跑，请先确认远端执行结果");
        }
        if (!Set.of("failed", "cancelled").contains(runStatus)) {
            throw new IllegalStateException("只有失败或已取消的流水线可以断点重跑");
        }
        if (!Set.of("failed", "cancelled").contains(nodeStatus)) {
            throw new IllegalStateException("只能从失败或已确认取消的节点继续");
        }
    }

    static List<Long> resumeOrder(List<Long> fullOrder, Set<Long> downstream, long resumeTaskId) {
        if (!fullOrder.contains(resumeTaskId)) {
            throw new IllegalStateException("续跑节点已不在当前任务依赖图中");
        }
        List<Long> order = fullOrder.stream().filter(downstream::contains).toList();
        if (order.isEmpty() || order.getFirst() != resumeTaskId) {
            throw new IllegalStateException("无法生成安全的续跑依赖链");
        }
        return order;
    }

    static String reconciledNodeStatus(String instanceStatus) {
        return switch (instanceStatus) {
            case "success" -> "succeeded";
            case "cancelled" -> "cancelled";
            case "failed", "timed_out" -> "failed";
            default -> throw new IllegalArgumentException("任务实例结果尚未完成核对");
        };
    }

    TaskInstance awaitTerminal(Task task, TaskInstance initial) {
        if (initial == null) return uncertain(null, "执行入口没有返回可核对结果");
        if (!Set.of("spark", "flink").contains(task.getTaskType())) return initial;
        long timeout = Math.max(30, task.getTimeoutSeconds() == null ? 1800 : task.getTimeoutSeconds()) * 1000L + 60_000;
        if(initial.getLeaseExpiresAt()!=null)timeout=Math.max(1,java.time.Duration.between(java.time.OffsetDateTime.now(),initial.getLeaseExpiresAt()).toMillis());
        long started = remoteWaitNanos();
        TaskInstance current = initial;
        while (awaitingRemoteResult(current)
                && remoteWaitNanos() - started < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout)) {
            try { pauseRemotePoll(); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return uncertain(initial, "流水线等待被中断，远端结果待核对");
            }
            try { current = instanceService.getById(initial.getId()); }
            catch (RuntimeException unavailable) { return uncertain(initial, "暂时无法读取远端执行结果"); }
            if (current == null || !java.util.Objects.equals(initial.getId(), current.getId())
                    || !java.util.Objects.equals(initial.getActiveAttemptId(), current.getActiveAttemptId())
                    || !java.util.Objects.equals(initial.getFencingToken(), current.getFencingToken())) {
                return uncertain(initial, "远端结果缺失或已切换到其他attempt");
            }
        }
        if (awaitingRemoteResult(current)) {
            return uncertain(initial, "远端节点状态在等待窗口内未能确认");
        }
        return current;
    }

    long remoteWaitNanos() { return System.nanoTime(); }
    void pauseRemotePoll() throws InterruptedException { Thread.sleep(1000); }

    /** A pipeline projection only: the physical attempt and its capacity remain owned. */
    private static TaskInstance uncertain(TaskInstance original, String reason) {
        TaskInstance result = new TaskInstance();
        if (original != null) {
            result.setId(original.getId());
            result.setActiveAttemptId(original.getActiveAttemptId());
            result.setFencingToken(original.getFencingToken());
        }
        result.setStatus("unknown");
        result.setErrorMessage(reason);
        return result;
    }

    private static boolean awaitingRemoteResult(TaskInstance instance) {
        if (instance.getStatus() == null) return false;
        return Set.of("queued", "submitting", "running", "cancel_requested").contains(instance.getStatus())
                || ("unknown".equals(instance.getStatus()) && instance.getFinishedAt() == null);
    }

    private Long activeWaiver(long taskId, String bizDate) {
        List<Long> ids = jdbc.query("""
                SELECT id FROM workflow.quality_waiver
                WHERE quality_task_id=? AND biz_date=? AND expires_at>now()
                ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> rs.getLong(1), taskId, bizDate);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private List<Long> topologicalOrder(long target) {
        Map<Long, List<Long>> upstream = new HashMap<>();
        collect(target, upstream, new HashSet<>());
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        visit(target, upstream, new HashSet<>(), new HashSet<>(), ordered);
        return new ArrayList<>(ordered);
    }

    private Set<Long> downstreamOf(long taskId) {
        return new HashSet<>(jdbc.query("""
                WITH RECURSIVE downstream(task_id) AS (
                    VALUES (CAST(? AS BIGINT))
                    UNION
                    SELECT dependency.task_id
                    FROM workflow.task_dependency dependency
                    JOIN downstream ON dependency.upstream_task_id=downstream.task_id
                )
                SELECT task_id FROM downstream
                """, (rs, row) -> rs.getLong(1), taskId));
    }

    /** Rejects an invalid dependency closure before creating any pipeline audit rows. */
    void validateRunnableTasks(List<Long> order) {
        for (Long taskId : order) {
            Task task = taskService.getById(taskId);
            if (task == null) {
                throw new IllegalStateException("流水线节点任务不存在：" + taskId);
            }
            validateRunnableTask(task);
        }
    }

    private static void validateRunnableTask(Task task) {
        if (!"enabled".equals(task.getStatus())) {
            throw new IllegalStateException("流水线节点「" + task.getName() + "」已停用");
        }
        String taskType = task.getTaskType() == null ? "sql" : task.getTaskType();
        if (!Set.of("sql", "spark", "flink", "quality").contains(taskType)) {
            throw new IllegalStateException("流水线节点「" + task.getName() + "」类型不受支持");
        }
    }

    private void collect(long taskId, Map<Long, List<Long>> upstream, Set<Long> seen) {
        if (!seen.add(taskId)) return;
        if(seen.size()>512)throw new IllegalArgumentException("流水线最多支持512个冻结节点");
        List<Long> parents = jdbc.query("""
                SELECT upstream_task_id FROM workflow.task_dependency WHERE task_id=? ORDER BY upstream_task_id
                """, (rs, row) -> rs.getLong(1), taskId);
        upstream.put(taskId, parents);
        parents.forEach(parent -> collect(parent, upstream, seen));
    }

    private void visit(long taskId, Map<Long, List<Long>> upstream, Set<Long> visiting,
                       Set<Long> visited, LinkedHashSet<Long> ordered) {
        if (visited.contains(taskId)) return;
        if (!visiting.add(taskId)) throw new IllegalStateException("任务依赖存在环");
        for (Long parent : upstream.getOrDefault(taskId, List.of())) visit(parent, upstream, visiting, visited, ordered);
        visiting.remove(taskId); visited.add(taskId); ordered.add(taskId);
    }

    private Map<String, Object> run(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM workflow.pipeline_run WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("流水线运行不存在");
        return publicRun(rows.getFirst());
    }

    static Map<String,Object> publicRun(Map<String,Object> row){var result=new HashMap<>(row);result.remove("invocation_json");result.remove("plan_json");result.remove("worker_id");return result;}
    static Map<String,Object> publicNode(Map<String,Object> row){var result=new HashMap<>(row);result.remove("definition_json");result.remove("approval_json");result.remove("dependency_evidence");return result;}

    private static void validateBizDate(String value) {
        com.aetheris.workflow.config.BusinessCalendar.parseExact(value);
    }

}
