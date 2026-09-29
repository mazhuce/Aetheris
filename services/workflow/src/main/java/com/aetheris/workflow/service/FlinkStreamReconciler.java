package com.aetheris.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Polls persisted Flink SQL Gateway handles so a continuous task survives a
 * workflow-service restart. A missing/unreachable Gateway is deliberately not
 * guessed as success or failure; the lease reconciler will quarantine it as
 * unknown until a later poll or an explicit operator resolution.
 */
@Service
public class FlinkStreamReconciler {
    private static final Logger log = LoggerFactory.getLogger(FlinkStreamReconciler.class);

    private final JdbcTemplate jdbc;
    private final FlinkSqlExecutionClient client;
    private final ExecutionClaimCoordinator coordinator;

    public FlinkStreamReconciler(
            JdbcTemplate jdbc,
            FlinkSqlExecutionClient client,
            ExecutionClaimCoordinator coordinator) {
        this.jdbc = jdbc;
        this.client = client;
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${aetheris.flink-sql-gateway.reconcile-interval-ms:5000}")
    public void reconcileActiveStreams() {
        List<RemoteAttempt> attempts = jdbc.query("""
                SELECT ea.id, ea.instance_id, ea.remote_handle, ea.flink_job_id, ea.flink_endpoint
                FROM workflow.execution_attempt ea
                JOIN workflow.task_instance ti ON ti.id=ea.instance_id
                WHERE ea.task_type='flink' AND ea.flink_mode='streaming'
                  AND ti.active_attempt_id=ea.id
                  AND (ea.status IN ('running','cancel_requested')
                       OR (ea.status='unknown' AND ea.flink_job_id IS NULL))
                  AND ea.remote_handle IS NOT NULL
                ORDER BY ea.id
                LIMIT 100
                """, (rs, rowNum) -> new RemoteAttempt(
                rs.getLong("id"), rs.getLong("instance_id"),
                rs.getString("remote_handle"), rs.getString("flink_endpoint"),
                rs.getString("flink_job_id")));
        for (RemoteAttempt attempt : attempts) {
            String[] handles = attempt.remoteHandle().split("/", 2);
            if (handles.length != 2) {
                log.warn("Skipping malformed Flink remote handle for attempt {}", attempt.attemptId());
                continue;
            }
            try {
                FlinkSqlExecutionClient.GatewayOperationStatus remote = client.operationStatus(
                        attempt.endpoint(), handles[0], handles[1]);
                if ("FINISHED".equals(remote.status())) {
                    // A streaming INSERT operation finishes when submission is
                    // acknowledged. Only the JobManager can prove job completion.
                    if (attempt.jobId() == null) {
                        String jobId = client.submittedJobId(attempt.endpoint(), handles[0], handles[1]);
                        if (jobId != null) coordinator.bindDiscoveredFlinkJob(
                                attempt.instanceId(), attempt.attemptId(), attempt.remoteHandle(), jobId);
                    }
                } else if (!remote.terminal()) {
                    coordinator.renewFlinkLease(attempt.instanceId(), attempt.attemptId(), attempt.remoteHandle());
                }
            } catch (RuntimeException error) {
                log.debug("Flink stream reconciliation deferred for attempt {}", attempt.attemptId(), error);
            }
        }
    }

    private record RemoteAttempt(long attemptId, long instanceId, String remoteHandle,
                                 String endpoint, String jobId) { }
}
