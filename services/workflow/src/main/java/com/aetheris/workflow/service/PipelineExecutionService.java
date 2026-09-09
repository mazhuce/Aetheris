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
        Long runId;
        try {
            runId = jdbc.queryForObject("""
                    INSERT INTO workflow.pipeline_run
                        (target_task_id,biz_date,status,triggered_by,heartbeat_at,lease_expires_at)
                    VALUES (?,?,'queued',?,now(),now() + (? * interval '1 second')) RETURNING id
                    """, Long.class, targetTaskId, bizDate, username, dispatchLeaseSeconds);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("该目标在此业务日期已有运行中的流水线", duplicate);
        }
        for (int index = 0; index < order.size(); index++) {
            jdbc.update("""
                    INSERT INTO workflow.pipeline_node_run(pipeline_run_id,task_id,sequence_no)
                    VALUES (?,?,?)
                    """, runId, order.get(index), index + 1);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    taskExecutor.execute(() -> executeRun(runId, bizDate, username, order));
                } catch (RuntimeException rejected) {
                    failRun(runId, null, "流水线执行器暂时不可用，请重新发起");
                }
            }
        });
        return run(runId);
    }

    public List<Map<String, Object>> list(int limit, String username, boolean includeAll) {
        int boundedLimit = Math.clamp(limit, 1, 100);
        if (!includeAll) {
            return jdbc.queryForList("""
                    SELECT run.*, task.name target_task_name
                    FROM workflow.pipeline_run run JOIN workflow.task task ON task.id=run.target_task_id
                    WHERE run.triggered_by=? OR task.owner=?
                    ORDER BY run.id DESC LIMIT ?
                    """, username, username, boundedLimit);
        }
        return jdbc.queryForList("""
                SELECT run.*, task.name target_task_name
                FROM workflow.pipeline_run run JOIN workflow.task task ON task.id=run.target_task_id
                ORDER BY run.id DESC LIMIT ?
                """, boundedLimit);
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> run = run(id);
        run.put("nodes", jdbc.queryForList("""
                SELECT node.*, task.name task_name, task.task_type
                FROM workflow.pipeline_node_run node
                JOIN workflow.task task ON task.id=node.task_id
                WHERE node.pipeline_run_id=? ORDER BY node.sequence_no
                """, id));
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
        int started = jdbc.update("""
                UPDATE workflow.pipeline_run
                SET status='running',started_at=now(),heartbeat_at=now(),updated_at=now()
                WHERE id=? AND status='queued'
                """, runId);
        if (started != 1) return;
        Long currentTaskId = null;
        try {
            for (Long taskId : order) {
                currentTaskId = taskId;
                Task task = taskService.getById(taskId);
                if (task == null) throw new IllegalStateException("流水线节点任务不存在：" + taskId);
                long taskLease = Math.max(30, task.getTimeoutSeconds() == null ? 1800 : task.getTimeoutSeconds())
                        + leaseGraceSeconds;
                if (!refreshLease(runId, taskLease)) return;
                jdbc.update("""
                        UPDATE workflow.pipeline_node_run SET status='running',started_at=now()
                        WHERE pipeline_run_id=? AND task_id=?
                        """, runId, taskId);
                TaskInstance instance = executionService.execute(taskId, bizDate, "pipeline", username);
                instance = awaitTerminal(task, instance);
                String nodeStatus = switch (instance.getStatus()) {
                    case "success" -> "succeeded";
                    case "unknown" -> "unknown";
                    default -> "failed";
                };
                Long waiverId = null;
                if ("quality".equals(task.getTaskType()) && !"success".equals(instance.getStatus())) {
                    waiverId = activeWaiver(taskId, bizDate);
                    if (waiverId != null) nodeStatus = "waived";
                }
                jdbc.update("""
                        UPDATE workflow.pipeline_node_run
                        SET status=?,instance_id=?,waiver_id=?,finished_at=now(),message=?
                        WHERE pipeline_run_id=? AND task_id=?
                        """,
                        nodeStatus, instance.getId(), waiverId,
                        bounded(instance.getErrorMessage(), MAX_NODE_MESSAGE_LENGTH), runId, taskId);
                if ("unknown".equals(nodeStatus)) {
                    suspendRun(runId, "节点「" + task.getName() + "」结果待核对，流水线已停止");
                    return;
                }
                if (!"succeeded".equals(nodeStatus) && !"waived".equals(nodeStatus)) {
                    throw new IllegalStateException("节点「" + task.getName() + "」执行失败");
                }
            }
            jdbc.update("""
                    UPDATE workflow.pipeline_run
                    SET status='succeeded',finished_at=now(),heartbeat_at=now(),
                        lease_expires_at=NULL,updated_at=now()
                    WHERE id=? AND status='running'
                    """, runId);
        } catch (RuntimeException error) {
            failRun(runId, currentTaskId,
                    error.getMessage() == null ? "流水线执行失败" : error.getMessage());
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
                    WHERE pipeline_run_id=? AND task_id=? AND status='running'
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

    private boolean refreshLease(long runId, long leaseSeconds) {
        return jdbc.update("""
                UPDATE workflow.pipeline_run
                SET heartbeat_at=now(),lease_expires_at=now() + (? * interval '1 second'),updated_at=now()
                WHERE id=? AND status='running'
                """, leaseSeconds, runId) == 1;
    }

    /** Releases stale active rows after a worker crash without guessing node outcomes. */
    @Scheduled(fixedDelayString = "${aetheris.pipeline.reconcile-interval-ms:30000}")
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

    static String bounded(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    private TaskInstance awaitTerminal(Task task, TaskInstance initial) {
        if (!"spark".equals(task.getTaskType())) return initial;
        long timeout = Math.max(30, task.getTimeoutSeconds() == null ? 1800 : task.getTimeoutSeconds()) * 1000L + 60_000;
        long deadline = System.currentTimeMillis() + timeout;
        TaskInstance current = initial;
        while (awaitingRemoteResult(current)
                && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(1000); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("流水线等待被中断");
            }
            current = instanceService.getById(initial.getId());
        }
        if (awaitingRemoteResult(current)) {
            throw new IllegalStateException("Spark 节点状态在等待窗口内未能确认");
        }
        return current;
    }

    private static boolean awaitingRemoteResult(TaskInstance instance) {
        return Set.of("submitting", "running", "cancel_requested").contains(instance.getStatus())
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

    /** Rejects an invalid dependency closure before creating any pipeline audit rows. */
    void validateRunnableTasks(List<Long> order) {
        for (Long taskId : order) {
            Task task = taskService.getById(taskId);
            if (task == null) {
                throw new IllegalStateException("流水线节点任务不存在：" + taskId);
            }
            if (!"enabled".equals(task.getStatus())) {
                throw new IllegalStateException("流水线节点「" + task.getName() + "」已停用");
            }
            String taskType = task.getTaskType() == null ? "sql" : task.getTaskType();
            if (!Set.of("sql", "spark", "quality").contains(taskType)) {
                throw new IllegalStateException("流水线节点「" + task.getName() + "」类型不受支持");
            }
        }
    }

    private void collect(long taskId, Map<Long, List<Long>> upstream, Set<Long> seen) {
        if (!seen.add(taskId)) return;
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
        return new HashMap<>(rows.getFirst());
    }

    private static void validateBizDate(String value) {
        try { java.time.LocalDate.parse(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("业务日期必须为 yyyy-MM-dd"); }
    }
}
