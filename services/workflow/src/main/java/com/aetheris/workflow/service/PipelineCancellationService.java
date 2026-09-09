package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.TaskInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cancels pipelines only when local or remote termination can be proven. */
@Service
public class PipelineCancellationService {
    private final JdbcTemplate jdbc;
    private final SparkBatchExecutionService sparkExecutions;
    private final TransactionOperations transactions;

    @Autowired
    public PipelineCancellationService(JdbcTemplate jdbc,
                                       SparkBatchExecutionService sparkExecutions,
                                       PlatformTransactionManager transactionManager) {
        this(jdbc, sparkExecutions, new TransactionTemplate(transactionManager));
    }

    PipelineCancellationService(JdbcTemplate jdbc, SparkBatchExecutionService sparkExecutions,
                                TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.sparkExecutions = sparkExecutions;
        this.transactions = transactions;
    }

    public CancellationResult cancel(long runId, String username) {
        Map<String, Object> run = run(runId);
        String status = String.valueOf(run.get("status"));
        if ("cancelled".equals(status)) return new CancellationResult(run, false);
        if ("queued".equals(status)) {
            Boolean cancelled = transactions.execute(ignored -> cancelQueued(runId, username));
            if (Boolean.TRUE.equals(cancelled)) return new CancellationResult(run(runId), false);
            run = run(runId);
            status = String.valueOf(run.get("status"));
        }
        if (!"running".equals(status)) {
            throw new IllegalStateException("只有排队中或运行中的流水线可以取消");
        }

        ActiveNode node = activeNode(runId);
        if (node == null) throw new IllegalStateException("流水线节点正在切换，请稍后重试");
        if (!"spark".equals(node.taskType()) || node.instanceId() == null) {
            throw new IllegalStateException("当前节点不支持可靠取消，未修改流水线状态");
        }

        TaskInstance instance = sparkExecutions.cancel(node.instanceId());
        if ("cancelled".equals(instance.getStatus())) {
            Boolean completed = transactions.execute(ignored ->
                    confirmRemoteCancellation(runId, node, username));
            if (!Boolean.TRUE.equals(completed) && !"cancelled".equals(String.valueOf(run(runId).get("status")))) {
                throw new IllegalStateException("流水线状态已变化，取消结果需要重新核对");
            }
            return new CancellationResult(run(runId), false);
        }
        if ("cancel_requested".equals(instance.getStatus())) {
            Map<String, Object> pending = new HashMap<>(run(runId));
            pending.put("cancellation_requested", true);
            return new CancellationResult(pending, true);
        }
        throw new IllegalStateException("Spark 节点已结束，取消未生效");
    }

    private boolean cancelQueued(long runId, String username) {
        int changed = jdbc.update("""
                UPDATE workflow.pipeline_run
                SET status='cancelled',error_message=?,finished_at=now(),heartbeat_at=now(),
                    lease_expires_at=NULL,updated_at=now()
                WHERE id=? AND status='queued'
                """, "由 " + username + " 在执行前取消", runId);
        if (changed != 1) return false;
        jdbc.update("""
                UPDATE workflow.pipeline_node_run
                SET status='skipped',finished_at=now(),message='流水线在执行前取消'
                WHERE pipeline_run_id=? AND status='queued'
                """, runId);
        return true;
    }

    private boolean confirmRemoteCancellation(long runId, ActiveNode node, String username) {
        int nodeChanged = jdbc.update("""
                UPDATE workflow.pipeline_node_run
                SET status='cancelled',finished_at=now(),message='Spark 远端已确认取消'
                WHERE id=? AND pipeline_run_id=? AND status='running'
                """, node.id(), runId);
        if (nodeChanged != 1) {
            Integer alreadyCancelled = jdbc.queryForObject("""
                    SELECT count(*) FROM workflow.pipeline_node_run
                    WHERE id=? AND pipeline_run_id=? AND status='cancelled'
                    """, Integer.class, node.id(), runId);
            if (alreadyCancelled == null || alreadyCancelled != 1) return false;
        }
        jdbc.update("""
                UPDATE workflow.pipeline_node_run
                SET status='skipped',finished_at=now(),message='流水线已取消，节点未执行'
                WHERE pipeline_run_id=? AND status='queued'
                """, runId);
        return jdbc.update("""
                UPDATE workflow.pipeline_run
                SET status='cancelled',error_message=?,finished_at=now(),heartbeat_at=now(),
                    lease_expires_at=NULL,updated_at=now()
                WHERE id=? AND status='running'
                """, "由 " + username + " 取消，Spark 远端已确认终止", runId) == 1;
    }

    private ActiveNode activeNode(long runId) {
        List<ActiveNode> nodes = jdbc.query("""
                SELECT node.id,node.instance_id,task.task_type,task.name
                FROM workflow.pipeline_node_run node
                JOIN workflow.task task ON task.id=node.task_id
                WHERE node.pipeline_run_id=? AND node.status='running'
                ORDER BY node.sequence_no LIMIT 1
                """, (rs, row) -> new ActiveNode(rs.getLong("id"),
                (Long) rs.getObject("instance_id"), rs.getString("task_type"),
                rs.getString("name")), runId);
        return nodes.isEmpty() ? null : nodes.getFirst();
    }

    private Map<String, Object> run(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM workflow.pipeline_run WHERE id=?", runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("流水线运行不存在");
        return new HashMap<>(rows.getFirst());
    }

    record ActiveNode(long id, Long instanceId, String taskType, String taskName) { }

    public record CancellationResult(Map<String, Object> run, boolean pending) { }
}
