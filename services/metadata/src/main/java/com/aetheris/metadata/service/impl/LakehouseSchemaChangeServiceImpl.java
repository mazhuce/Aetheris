package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.LakehouseSchemaChange;
import com.aetheris.metadata.entity.LakehouseTable;
import com.aetheris.metadata.mapper.LakehouseSchemaChangeMapper;
import com.aetheris.metadata.service.LakehouseSchemaChangeService;
import com.aetheris.metadata.service.LakehouseMaintenanceExecutionClient;
import com.aetheris.metadata.service.SchemaEvolutionSqlPlanner;
import com.aetheris.metadata.service.LakehouseTableService;
import com.aetheris.metadata.service.SchemaEvolutionPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LakehouseSchemaChangeServiceImpl extends ServiceImpl<LakehouseSchemaChangeMapper, LakehouseSchemaChange>
        implements LakehouseSchemaChangeService {
    private final LakehouseTableService tableService;
    private final LakehouseMaintenanceExecutionClient executionClient;
    private final ObjectMapper objectMapper;

    /** Small constructor retained for validation-only unit tests. */
    public LakehouseSchemaChangeServiceImpl(LakehouseTableService tableService) {
        this(tableService, null, new ObjectMapper());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LakehouseSchemaChangeServiceImpl(LakehouseTableService tableService,
                                             LakehouseMaintenanceExecutionClient executionClient,
                                             ObjectMapper objectMapper) {
        this.tableService = tableService;
        this.executionClient = executionClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public LakehouseSchemaChange propose(Long tableId, LakehouseSchemaChange change, String actor) {
        if (tableService.getById(tableId) == null) throw new IllegalArgumentException("湖仓表不存在");
        if (!StringUtils.hasText(change.getColumnName())) throw new IllegalArgumentException("columnName 不能为空");
        change.setLakehouseTableId(tableId);
        change.setDecision(SchemaEvolutionPolicy.decision(change.getOperation()));
        change.setStatus("proposed");
        change.setRequestedBy(actor);
        if (!save(change)) throw new IllegalStateException("Schema 变更登记失败");
        return getById(change.getId());
    }

    @Override
    @Transactional
    public LakehouseSchemaChange review(Long id, boolean approved, String actor) {
        LakehouseSchemaChange change = require(id);
        if (!"proposed".equals(change.getStatus())) throw new IllegalArgumentException("只有 proposed 变更可以审批");
        if (!"approval_required".equals(change.getDecision())) {
            throw new IllegalArgumentException("该变更类型不需要审批，请由受管执行器处理");
        }
        change.setStatus(approved ? "approved" : "rejected");
        change.setReviewedBy(actor);
        change.setReviewedAt(OffsetDateTime.now());
        updateById(change);
        return getById(id);
    }

    @Override
    @Transactional
    public LakehouseSchemaChange isolate(Long id, String actor) {
        LakehouseSchemaChange change = require(id);
        if (!"proposed".equals(change.getStatus())) throw new IllegalArgumentException("只有 proposed 变更可以隔离");
        change.setStatus("isolated");
        change.setReviewedBy(actor);
        change.setReviewedAt(OffsetDateTime.now());
        updateById(change);
        return getById(id);
    }

    @Override
    public List<LakehouseSchemaChange> listForTable(Long tableId) {
        if (tableService.getById(tableId) == null) throw new IllegalArgumentException("湖仓表不存在");
        return list(new LambdaQueryWrapper<LakehouseSchemaChange>()
                .eq(LakehouseSchemaChange::getLakehouseTableId, tableId)
                .orderByDesc(LakehouseSchemaChange::getCreatedAt));
    }

    @Override
    @Transactional
    public LakehouseSchemaChange execute(Long id) {
        if (executionClient == null) throw new IllegalStateException("Schema 执行器未配置");
        LakehouseSchemaChange change = require(id);
        if (!Set.of("proposed", "approved", "failed", "cancelled").contains(change.getStatus())) {
            throw new IllegalStateException("当前 Schema 变更状态不可执行");
        }
        LakehouseTable table = tableService.getById(change.getLakehouseTableId());
        SchemaEvolutionSqlPlanner.Plan plan = SchemaEvolutionSqlPlanner.plan(table, change);
        if (!plan.readyForExecution()) throw new IllegalStateException("Schema 变更尚未满足执行条件");
        String requestKey = executionRequestKey(change);
        change.setStatus("executing");
        change.setExecutionRequestKey(requestKey);
        change.setExecutionError(null);
        try {
            Map<String, Object> response = executionClient.submit(Map.of(
                    "requestKey", requestKey,
                    "bizDate", java.time.LocalDate.now().toString(),
                    "attemptId", String.valueOf(change.getId()),
                    "operation", plan.operation(),
                    "statement", plan.statement(),
                    "timeoutSeconds", 86400));
            change.setRemoteHandle(stringValue(response.get("id")));
            change.setExecutionResultJson(writeJson(response));
        } catch (RuntimeException unknown) {
            // The request may have reached workflow; only reconcile can decide its result.
            change.setExecutionError(bounded(unknown.getMessage()));
            change.setExecutionResultJson(writeJson(Map.of("dispatch", "unknown", "requestKey", requestKey)));
        }
        updateById(change);
        return getById(id);
    }

    @Override
    @Transactional
    public LakehouseSchemaChange reconcile(Long id) {
        if (executionClient == null) throw new IllegalStateException("Schema 执行器未配置");
        LakehouseSchemaChange change = require(id);
        if (!"executing".equals(change.getStatus()) || !StringUtils.hasText(change.getExecutionRequestKey())) {
            return change;
        }
        var remote = executionClient.status(change.getExecutionRequestKey());
        if (remote.isEmpty()) return change;
        Map<String, Object> execution = remote.get();
        String status = stringValue(execution.get("status"));
        change.setExecutionResultJson(writeJson(execution));
        if ("succeeded".equals(status)) {
            change.setStatus("applied");
            change.setAppliedAt(OffsetDateTime.now());
            change.setExecutionError(null);
        } else if ("failed".equals(status) || "timed_out".equals(status)) {
            change.setStatus("failed");
            change.setExecutionError(bounded(stringValue(execution.get("errorMessage"))));
        } else if ("cancelled".equals(status)) {
            change.setStatus("cancelled");
        } else {
            return change;
        }
        updateById(change);
        return getById(id);
    }

    @Scheduled(fixedDelayString = "${aetheris.lakehouse.schema-reconcile-interval-ms:5000}")
    public void reconcileExecuting() {
        if (executionClient == null) return;
        List<LakehouseSchemaChange> running = list(new LambdaQueryWrapper<LakehouseSchemaChange>()
                .eq(LakehouseSchemaChange::getStatus, "executing")
                .isNotNull(LakehouseSchemaChange::getExecutionRequestKey)
                .orderByAsc(LakehouseSchemaChange::getCreatedAt)
                .last("LIMIT 32"));
        for (LakehouseSchemaChange change : running) {
            try { reconcile(change.getId()); } catch (RuntimeException ignored) { }
        }
    }

    @Override
    @Transactional
    public LakehouseSchemaChange cancel(Long id) {
        if (executionClient == null) throw new IllegalStateException("Schema 执行器未配置");
        LakehouseSchemaChange change = require(id);
        if (!"executing".equals(change.getStatus())) throw new IllegalStateException("只有 executing Schema 变更可以取消");
        if (!StringUtils.hasText(change.getRemoteHandle())) {
            throw new IllegalStateException("Schema 变更没有可确认的远端句柄");
        }
        try {
            Map<String, Object> response = executionClient.cancel(change.getRemoteHandle());
            change.setExecutionResultJson(writeJson(response));
            if ("cancelled".equals(stringValue(response.get("status")))) {
                change.setStatus("cancelled");
            } else {
                change.setExecutionError("已发起取消，等待远端确认");
            }
        } catch (RuntimeException unknown) {
            change.setExecutionError(bounded(unknown.getMessage()));
        }
        updateById(change);
        return getById(id);
    }

    private LakehouseSchemaChange require(Long id) {
        LakehouseSchemaChange change = id == null ? null : getById(id);
        if (change == null) throw new IllegalArgumentException("Schema 变更不存在");
        return change;
    }

    private static String executionRequestKey(LakehouseSchemaChange change) {
        String raw = change.getId() + ":" + change.getOperation() + ":" + change.getColumnName();
        try {
            return "lakehouse-schema-" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 48);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Schema 执行结果序列化失败", error); }
    }

    private static String stringValue(Object value) { return value == null ? null : value.toString(); }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "Schema 执行状态未知，等待对账";
        return value.length() > 2048 ? value.substring(0, 2048) : value;
    }
}
