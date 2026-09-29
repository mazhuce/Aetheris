package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.LakehouseMaintenanceJob;
import com.aetheris.metadata.entity.LakehouseTable;
import com.aetheris.metadata.mapper.LakehouseMaintenanceJobMapper;
import com.aetheris.metadata.service.LakehouseMaintenanceJobService;
import com.aetheris.metadata.service.LakehouseMaintenancePlan;
import com.aetheris.metadata.service.LakehouseMaintenanceExecutionClient;
import com.aetheris.metadata.service.LakehouseTableService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LakehouseMaintenanceJobServiceImpl extends ServiceImpl<LakehouseMaintenanceJobMapper, LakehouseMaintenanceJob>
        implements LakehouseMaintenanceJobService {
    private static final Set<String> OPERATIONS = Set.of(
            "expire_snapshots", "rewrite_data_files", "remove_orphan_files",
            "storage_estimate", "time_travel", "rollback");
    private final LakehouseTableService tableService;
    private final LakehouseMaintenanceExecutionClient executionClient;
    private final ObjectMapper objectMapper;

    /** Small constructor retained for isolated unit tests that only exercise validation. */
    public LakehouseMaintenanceJobServiceImpl(LakehouseTableService tableService) {
        this(tableService, null, new ObjectMapper());
    }

    @Autowired
    public LakehouseMaintenanceJobServiceImpl(LakehouseTableService tableService,
                                              LakehouseMaintenanceExecutionClient executionClient,
                                              ObjectMapper objectMapper) {
        this.tableService = tableService;
        this.executionClient = executionClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public LakehouseMaintenanceJob request(Long tableId, LakehouseMaintenanceJob job, String actor) {
        if (tableService.getById(tableId) == null) throw new IllegalArgumentException("湖仓表不存在");
        if (!OPERATIONS.contains(job.getOperation())) throw new IllegalArgumentException("不支持的湖仓维护操作");
        if (!StringUtils.hasText(job.getIdempotencyKey())) throw new IllegalArgumentException("idempotencyKey 不能为空");
        job.setLakehouseTableId(tableId);
        job.setStatus("requested");
        job.setRequestedBy(actor);
        if (!StringUtils.hasText(job.getRequestJson())) job.setRequestJson("{}");
        LakehouseMaintenanceJob existing = getOne(new LambdaQueryWrapper<LakehouseMaintenanceJob>()
                .eq(LakehouseMaintenanceJob::getLakehouseTableId, tableId)
                .eq(LakehouseMaintenanceJob::getOperation, job.getOperation())
                .eq(LakehouseMaintenanceJob::getIdempotencyKey, job.getIdempotencyKey())
                .last("LIMIT 1"));
        if (existing != null) return existing;
        if (!save(job)) throw new IllegalStateException("湖仓维护作业登记失败");
        return getById(job.getId());
    }

    @Override
    public List<LakehouseMaintenanceJob> listForTable(Long tableId) {
        if (tableService.getById(tableId) == null) throw new IllegalArgumentException("湖仓表不存在");
        return list(new LambdaQueryWrapper<LakehouseMaintenanceJob>()
                .eq(LakehouseMaintenanceJob::getLakehouseTableId, tableId)
                .orderByDesc(LakehouseMaintenanceJob::getCreatedAt));
    }

    @Override
    @Transactional
    public LakehouseMaintenanceJob claim(Long jobId) {
        LakehouseMaintenanceJob job = require(jobId);
        if (baseMapper.claimRequested(jobId) != 1) throw new IllegalStateException("作业不是 requested 状态或已被其他执行器领取");
        return getById(job.getId());
    }

    @Override
    @Transactional
    public LakehouseMaintenanceJob execute(Long jobId) {
        if (executionClient == null) throw new IllegalStateException("湖仓维护执行器未配置");
        LakehouseMaintenanceJob current = require(jobId);
        LakehouseTable table = tableService.getById(current.getLakehouseTableId());
        if (table == null) throw new IllegalArgumentException("湖仓表不存在");
        LakehouseMaintenancePlan.Plan plan = LakehouseMaintenancePlan.build(table, current);
        String requestKey = executionRequestKey(current);
        LakehouseMaintenanceJob running = claim(jobId);
        running.setExecutorRequestKey(requestKey);
        try {
            Map<String, Object> response = executionClient.submit(Map.of(
                    "requestKey", requestKey,
                    "bizDate", java.time.LocalDate.now().toString(),
                    "attemptId", String.valueOf(running.getId()),
                    "operation", plan.operation(),
                    "statement", plan.statement(),
                    "timeoutSeconds", 86400));
            running.setRemoteHandle(stringValue(response.get("id")));
            running.setResultJson(writeJson(response));
            running.setLastError(null);
        } catch (RuntimeException unknown) {
            // The POST may have reached workflow. Keep the job running with its
            // deterministic idempotency key; reconciliation must decide later.
            running.setLastError(bounded(unknown.getMessage()));
            running.setResultJson(writeJson(Map.of("dispatch", "unknown", "requestKey", requestKey)));
        }
        updateById(running);
        return getById(jobId);
    }

    @Override
    @Transactional
    public LakehouseMaintenanceJob reconcile(Long jobId) {
        if (executionClient == null) throw new IllegalStateException("湖仓维护执行器未配置");
        LakehouseMaintenanceJob job = require(jobId);
        if (!"running".equals(job.getStatus()) || !StringUtils.hasText(job.getExecutorRequestKey())) return job;
        var remote = executionClient.status(job.getExecutorRequestKey());
        if (remote.isEmpty()) return job; // no result is not failure; keep unknown/running
        var execution = remote.get();
        String status = execution.get("status") == null ? "unknown" : execution.get("status").toString();
        if ("succeeded".equals(status)) {
            return complete(jobId, "succeeded", writeJson(execution.get("result") == null ? execution : execution.get("result")), null);
        }
        if ("failed".equals(status) || "timed_out".equals(status)) {
            return complete(jobId, "failed", writeJson(execution), bounded(stringValue(execution.get("errorMessage"))));
        }
        if ("cancelled".equals(status)) {
            return complete(jobId, "cancelled", writeJson(execution), null);
        }
        return job;
    }

    /** Reconcile only jobs with a durable request key; never submits a new job. */
    @Scheduled(fixedDelayString = "${aetheris.lakehouse.maintenance-reconcile-interval-ms:5000}")
    public void reconcileRunningJobs() {
        if (executionClient == null) return;
        List<LakehouseMaintenanceJob> running = list(new LambdaQueryWrapper<LakehouseMaintenanceJob>()
                .eq(LakehouseMaintenanceJob::getStatus, "running")
                .isNotNull(LakehouseMaintenanceJob::getExecutorRequestKey)
                .orderByAsc(LakehouseMaintenanceJob::getCreatedAt)
                .last("LIMIT 32"));
        for (LakehouseMaintenanceJob job : running) {
            try { reconcile(job.getId()); }
            catch (RuntimeException ignored) { /* unavailable remains running for a later pass */ }
        }
    }

    @Override
    @Transactional
    public LakehouseMaintenanceJob complete(Long jobId, String status, String resultJson, String error) {
        LakehouseMaintenanceJob job = require(jobId);
        if (!"running".equals(job.getStatus())) throw new IllegalStateException("只有 running 作业可以完成");
        if (!Set.of("succeeded", "failed", "cancelled").contains(status)) throw new IllegalArgumentException("完成状态非法");
        job.setStatus(status);
        job.setResultJson(resultJson);
        job.setLastError(error);
        job.setFinishedAt(OffsetDateTime.now());
        updateById(job);
        return getById(jobId);
    }

    @Override
    @Transactional
    public LakehouseMaintenanceJob cancel(Long jobId) {
        LakehouseMaintenanceJob job = require(jobId);
        if (!Set.of("requested", "running").contains(job.getStatus())) throw new IllegalStateException("当前状态不可取消");
        if ("running".equals(job.getStatus())) {
            if (executionClient == null || !StringUtils.hasText(job.getRemoteHandle())) {
                throw new IllegalStateException("运行中的维护作业没有可确认的远端句柄");
            }
            try {
                Map<String, Object> response = executionClient.cancel(job.getRemoteHandle());
                String remoteStatus = stringValue(response.get("status"));
                job.setResultJson(writeJson(response));
                if (!"cancelled".equals(remoteStatus)) {
                    job.setLastError("已发起取消，等待远端确认");
                    updateById(job);
                    return getById(jobId);
                }
            } catch (RuntimeException unknown) {
                job.setLastError(bounded(unknown.getMessage()));
                updateById(job);
                return getById(jobId);
            }
        }
        job.setStatus("cancelled");
        job.setFinishedAt(OffsetDateTime.now());
        updateById(job);
        return getById(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public LakehouseMaintenancePlan.Plan preview(Long jobId) {
        LakehouseMaintenanceJob job = require(jobId);
        LakehouseTable table = tableService.getById(job.getLakehouseTableId());
        if (table == null) throw new IllegalArgumentException("湖仓表不存在");
        return LakehouseMaintenancePlan.build(table, job);
    }

    private LakehouseMaintenanceJob require(Long jobId) {
        LakehouseMaintenanceJob job = jobId == null ? null : getById(jobId);
        if (job == null) throw new IllegalArgumentException("湖仓维护作业不存在");
        return job;
    }

    private static String executionRequestKey(LakehouseMaintenanceJob job) {
        String raw = job.getId() + ":" + job.getOperation() + ":" + job.getIdempotencyKey();
        try {
            return "lakehouse-maintenance-" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 48);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("维护结果序列化失败", error); }
    }

    private static String stringValue(Object value) { return value == null ? null : value.toString(); }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "维护执行状态未知，等待对账";
        return value.length() > 2048 ? value.substring(0, 2048) : value;
    }
}
