package com.aetheris.workflow.service;

import com.aetheris.workflow.service.ExecutionClaimCoordinator.ExecutionClaim;
import com.aetheris.workflow.service.ManagedSparkCatalogService.ComputeEnvironmentView;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Durable FIFO admission and capacity accounting shared by all workflow replicas. */
@Service
public class SparkResourceQueueService {

    private static final Pattern RUNNER_KEY = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final Set<String> RUNNER_STATUSES = Set.of("active", "draining", "disabled");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ManagedSparkCatalogService catalog;
    private final SparkBatchExecutionAdapter adapter;

    public SparkResourceQueueService(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ManagedSparkCatalogService catalog, SparkBatchExecutionAdapter adapter) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.catalog = catalog;
        this.adapter = adapter;
    }

    @Transactional
    public void enqueue(ExecutionClaim claim, SparkJobView job) {
        int inserted = jdbc.update("""
                INSERT INTO workflow.spark_resource_allocation
                    (attempt_id,environment_id,requested_cores,requested_memory_mb,status)
                VALUES (?,?,?,?,'queued') ON CONFLICT (attempt_id) DO NOTHING
                """, claim.attemptId(), job.environmentId(), job.driverCores(), job.driverMemoryMb());
        if (inserted == 0) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM workflow.spark_resource_allocation
                    WHERE attempt_id=? AND status IN ('queued','admitted')
                    """, Integer.class, claim.attemptId());
            if (count == null || count == 0) throw new IllegalStateException("Spark 资源申请已结束，不能重复排队");
        } else {
            jdbc.update("""
                    INSERT INTO workflow.execution_event
                        (attempt_id,event_type,from_status,to_status,message,details_json)
                    VALUES (?,'resource_queued','queued','queued','等待 Runner 资源容量',
                        jsonb_build_object('environmentId',?,'cores',?,'memoryMb',?))
                    """, claim.attemptId(), job.environmentId(), job.driverCores(), job.driverMemoryMb());
        }
    }

    /** Attempts to admit this exact attempt; strict FIFO is enforced per environment. */
    public Admission tryAdmit(long attemptId) {
        return transactions.execute(status -> admitLocked(attemptId));
    }

    private Admission admitLocked(long attemptId) {
        List<QueueHead> heads = jdbc.query("""
                SELECT allocation.id,allocation.attempt_id,allocation.environment_id,
                       allocation.requested_cores,allocation.requested_memory_mb
                FROM workflow.spark_resource_allocation allocation
                JOIN workflow.execution_attempt attempt ON attempt.id=allocation.attempt_id
                JOIN workflow.task_instance instance ON instance.id=attempt.instance_id
                JOIN workflow.spark_job_definition job ON job.id=attempt.job_definition_id
                WHERE allocation.attempt_id=? AND allocation.status='queued'
                  AND attempt.status='queued' AND instance.status='queued'
                  AND instance.active_attempt_id=attempt.id
                  AND NOT EXISTS (
                    SELECT 1 FROM workflow.spark_resource_allocation older
                    WHERE older.environment_id=allocation.environment_id AND older.status='queued'
                      AND (older.queued_at,older.id)<(allocation.queued_at,allocation.id)
                  )
                FOR UPDATE OF allocation,attempt,instance
                """, (rs, row) -> new QueueHead(
                rs.getLong("id"), rs.getLong("attempt_id"), rs.getLong("environment_id"),
                rs.getInt("requested_cores"), rs.getInt("requested_memory_mb")), attemptId);
        if (heads.isEmpty()) return null;
        QueueHead head = heads.getFirst();

        List<Admission> runners = jdbc.query("""
                SELECT runner.id,runner.runner_key,runner.base_url
                FROM workflow.compute_runner runner
                JOIN workflow.spark_job_definition job ON job.id=(
                    SELECT attempt.job_definition_id FROM workflow.execution_attempt attempt
                    WHERE attempt.id=?
                )
                WHERE runner.environment_id=? AND runner.status='active'
                  AND (job.runner_id IS NULL OR job.runner_id=runner.id)
                  AND runner.max_cores>=? AND runner.max_memory_mb>=?
                  AND (SELECT count(*) FROM workflow.spark_resource_allocation active
                       WHERE active.runner_id=runner.id AND active.status='admitted') < runner.max_concurrency
                  AND COALESCE((SELECT sum(active.requested_cores)
                       FROM workflow.spark_resource_allocation active
                       WHERE active.runner_id=runner.id AND active.status='admitted'),0)+? <= runner.max_cores
                  AND COALESCE((SELECT sum(active.requested_memory_mb)
                       FROM workflow.spark_resource_allocation active
                       WHERE active.runner_id=runner.id AND active.status='admitted'),0)+? <= runner.max_memory_mb
                ORDER BY (SELECT count(*) FROM workflow.spark_resource_allocation active
                          WHERE active.runner_id=runner.id AND active.status='admitted'),runner.id
                FOR UPDATE OF runner SKIP LOCKED LIMIT 1
                """, (rs, row) -> new Admission(
                head.attemptId(), rs.getLong("id"), rs.getString("runner_key"), rs.getString("base_url")),
                head.attemptId(), head.environmentId(), head.cores(), head.memoryMb(), head.cores(), head.memoryMb());
        if (runners.isEmpty()) return null;
        Admission admission = runners.getFirst();

        int allocation = jdbc.update("""
                UPDATE workflow.spark_resource_allocation
                SET runner_id=?,status='admitted',admitted_at=now()
                WHERE id=? AND status='queued'
                """, admission.runnerId(), head.allocationId());
        int attempt = jdbc.update("""
                UPDATE workflow.execution_attempt
                SET status='submitting',runner_id=?,admitted_at=now(),heartbeat_at=now()
                WHERE id=? AND status='queued'
                """, admission.runnerId(), head.attemptId());
        int instance = jdbc.update("""
                UPDATE workflow.task_instance instance
                SET status='submitting',heartbeat_at=now(),updated_at=now()
                FROM workflow.execution_attempt attempt
                WHERE instance.active_attempt_id=attempt.id AND attempt.id=?
                  AND instance.status='queued' AND attempt.status='submitting'
                """, head.attemptId());
        if (allocation != 1 || attempt != 1 || instance != 1) {
            throw new IllegalStateException("Spark 排队状态已变化，请等待下一轮调度");
        }
        jdbc.update("""
                INSERT INTO workflow.execution_event
                    (attempt_id,event_type,from_status,to_status,message,details_json)
                VALUES (?,'resource_admitted','queued','submitting','Runner 容量已授予',
                    jsonb_build_object('runnerId',?,'runnerKey',?))
                """, head.attemptId(), admission.runnerId(), admission.runnerKey());
        return admission;
    }

    public List<Admission> admitQueued(int limit) {
        List<Long> ids = jdbc.query("""
                SELECT allocation.attempt_id
                FROM workflow.spark_resource_allocation allocation
                JOIN workflow.execution_attempt attempt ON attempt.id=allocation.attempt_id
                WHERE allocation.status='queued' AND attempt.status='queued'
                ORDER BY allocation.queued_at,allocation.id LIMIT ?
                """, (rs, row) -> rs.getLong(1), Math.clamp(limit, 1, 100));
        List<Admission> admitted = new ArrayList<>();
        for (Long id : ids) {
            Admission result = tryAdmit(id);
            if (result != null) admitted.add(result);
        }
        return admitted;
    }

    @Transactional
    public void release(long attemptId, String reason) {
        int released = jdbc.update("""
                UPDATE workflow.spark_resource_allocation
                SET status='released',released_at=now(),release_reason=?
                WHERE attempt_id=? AND status IN ('queued','admitted')
                """, bounded(reason, 128), attemptId);
        if (released == 1) {
            jdbc.update("""
                    INSERT INTO workflow.execution_event
                        (attempt_id,event_type,message,details_json)
                    VALUES (?,'resource_released','Runner 容量已释放',
                        jsonb_build_object('reason',?))
                    """, attemptId, bounded(reason, 128));
        }
    }

    public int releaseTerminalAllocations() {
        return jdbc.update("""
                WITH released AS (
                    UPDATE workflow.spark_resource_allocation allocation
                    SET status='released',released_at=now(),release_reason='terminal_reconciliation'
                    FROM workflow.execution_attempt attempt
                    WHERE attempt.id=allocation.attempt_id
                      AND allocation.status IN ('queued','admitted')
                      AND attempt.status IN ('succeeded','failed','cancelled','timed_out','skipped')
                    RETURNING allocation.attempt_id
                )
                INSERT INTO workflow.execution_event
                    (attempt_id,event_type,message,details_json)
                SELECT attempt_id,'resource_released','Runner 容量已由终态恢复器释放',
                       jsonb_build_object('reason','terminal_reconciliation')
                FROM released
                """);
    }

    public List<RunnerView> runners() {
        return jdbc.query("""
                SELECT runner.id,runner.environment_id,environment.name environment_name,
                       runner.runner_key,runner.name,runner.base_url,runner.max_cores,
                       runner.max_memory_mb,runner.max_concurrency,runner.status,runner.created_by,
                       runner.created_at,runner.updated_at,
                       COALESCE(usage.active_jobs,0) active_jobs,
                       COALESCE(usage.used_cores,0) used_cores,
                       COALESCE(usage.used_memory_mb,0) used_memory_mb,
                       COALESCE(queue.queued_jobs,0) queued_jobs
                FROM workflow.compute_runner runner
                JOIN workflow.compute_environment environment ON environment.id=runner.environment_id
                LEFT JOIN (
                    SELECT runner_id,count(*) active_jobs,sum(requested_cores) used_cores,
                           sum(requested_memory_mb) used_memory_mb
                    FROM workflow.spark_resource_allocation WHERE status='admitted' GROUP BY runner_id
                ) usage ON usage.runner_id=runner.id
                LEFT JOIN (
                    SELECT environment_id,count(*) queued_jobs
                    FROM workflow.spark_resource_allocation WHERE status='queued' GROUP BY environment_id
                ) queue ON queue.environment_id=runner.environment_id
                ORDER BY runner.environment_id,runner.id
                """, (rs, row) -> new RunnerView(
                rs.getLong("id"), rs.getLong("environment_id"), rs.getString("environment_name"),
                rs.getString("runner_key"), rs.getString("name"), rs.getString("base_url"),
                rs.getInt("max_cores"), rs.getInt("max_memory_mb"), rs.getInt("max_concurrency"),
                rs.getString("status"), rs.getInt("active_jobs"), rs.getInt("used_cores"),
                rs.getInt("used_memory_mb"), rs.getInt("queued_jobs"), rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)));
    }

    public RunnerView register(RunnerInput input, String username) {
        if (input.environmentId() == null) throw new IllegalArgumentException("必须选择计算环境");
        String key = required(input.runnerKey(), "Runner 标识不能为空").toLowerCase();
        if (!RUNNER_KEY.matcher(key).matches()) throw new IllegalArgumentException("Runner 标识格式不合法");
        String name = required(input.name(), "Runner 名称不能为空");
        if (name.length() > 128) throw new IllegalArgumentException("Runner 名称不能超过 128 个字符");
        String baseUrl = SparkBatchExecutionAdapter.normalizeBaseUrl(input.baseUrl());
        ComputeEnvironmentView environment = catalog.environment(input.environmentId());
        validateCapacity(input.maxCores(), input.maxMemoryMb(), input.maxConcurrency());
        if (!"active".equals(environment.status())) throw new IllegalStateException("计算环境已停用");
        ExecutionAdapter.AdapterHealth health = adapter.health(baseUrl);
        if (!health.up()) throw new IllegalStateException("Runner 健康检查未通过");
        if (health.maxConcurrency() > 0 && input.maxConcurrency() > health.maxConcurrency()) {
            throw new IllegalArgumentException("配置并发上限超过 Runner 实际上限");
        }
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO workflow.compute_runner
                        (environment_id,runner_key,name,base_url,max_cores,max_memory_mb,
                         max_concurrency,status,created_by)
                    VALUES (?,?,?,?,?,?,?,'active',?) RETURNING id
                    """, Long.class, input.environmentId(), key, name, baseUrl, input.maxCores(),
                    input.maxMemoryMb(), input.maxConcurrency(), username);
            return runner(id);
        } catch (DataIntegrityViolationException conflict) {
            throw new IllegalStateException("Runner 标识或地址已经存在");
        }
    }

    public RunnerView changeStatus(long id, String requestedStatus) {
        String status = required(requestedStatus, "Runner 状态不能为空").toLowerCase();
        if (!RUNNER_STATUSES.contains(status)) throw new IllegalArgumentException("Runner 状态不合法");
        RunnerView current = runner(id);
        if ("active".equals(status) && !adapter.health(current.baseUrl()).up()) {
            throw new IllegalStateException("Runner 健康检查未通过，不能启用");
        }
        transactions.executeWithoutResult(transaction -> {
            List<Long> locked = jdbc.query("""
                    SELECT id FROM workflow.compute_runner WHERE id=? FOR UPDATE
                    """, (rs, row) -> rs.getLong(1), id);
            if (locked.isEmpty()) throw new IllegalArgumentException("Runner 不存在");
            Integer active = jdbc.queryForObject("""
                    SELECT count(*) FROM workflow.spark_resource_allocation
                    WHERE runner_id=? AND status='admitted'
                    """, Integer.class, id);
            if ("disabled".equals(status) && active != null && active > 0) {
                throw new IllegalStateException("Runner 仍有活动作业，请先置为 draining 并等待释放");
            }
            jdbc.update("UPDATE workflow.compute_runner SET status=?,updated_at=now() WHERE id=?", status, id);
        });
        return runner(id);
    }

    private RunnerView runner(long id) {
        return runners().stream().filter(value -> value.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Runner 不存在"));
    }

    private static void validateCapacity(Integer cores, Integer memoryMb, Integer concurrency) {
        if (cores == null || memoryMb == null || concurrency == null
                || cores < 1 || cores > 256 || memoryMb < 512 || memoryMb > 1_048_576
                || concurrency < 1 || concurrency > 256) {
            throw new IllegalArgumentException("Runner 容量参数超出允许范围");
        }
    }

    private static String required(String value, String message) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String bounded(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    private record QueueHead(long allocationId, long attemptId, long environmentId, int cores, int memoryMb) { }

    public record Admission(long attemptId, long runnerId, String runnerKey, String baseUrl) { }

    public record RunnerInput(
            Long environmentId, String runnerKey, String name, String baseUrl,
            Integer maxCores, Integer maxMemoryMb, Integer maxConcurrency) { }

    public record RunnerStatusInput(String status) { }

    public record RunnerView(
            long id, long environmentId, String environmentName, String runnerKey, String name,
            String baseUrl, int maxCores, int maxMemoryMb, int maxConcurrency, String status,
            int activeJobs, int usedCores, int usedMemoryMb, int queuedJobs, String createdBy,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
}
