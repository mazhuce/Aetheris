package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.AdhocQueryRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.QueryHistory;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.security.AetherisIdentity;
import com.aetheris.metadata.security.QueryRequestContext;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.QueryHistoryService;
import com.aetheris.metadata.service.QueryAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 即席查询端点：登录用户在指定数据源上执行只读 SQL。
 *
 * <p>与 {@code /internal/agent/query} 共用 {@code JdbcService.query} 的
 * {@code validateReadOnlySql} 守卫：只放行 SELECT / SHOW / DESCRIBE / EXPLAIN，
 * 拒绝写入与危险函数——人工输入与 agent 生成的 SQL 受同一套约束。
 * 行数上限与查询超时也由 JdbcService 统一控制。
 *
 * <p>执行前经 auth-service 决策引擎做 datasource.query 判定，用户名取网关
 * 验签身份（不再信任 X-User-Name 请求头）。
 */
@RestController
@RequestMapping("/api/metadata/query")
public class AdhocQueryController {

    private static final Logger log = LoggerFactory.getLogger(AdhocQueryController.class);

    private final DataSourceService dataSourceService;
    private final JdbcService jdbcService;
    private final QueryHistoryService queryHistoryService;
    private final MetadataSecurity security;
    private final QueryAuditService auditService;

    @Autowired
    public AdhocQueryController(DataSourceService dataSourceService, JdbcService jdbcService,
                                QueryHistoryService queryHistoryService, MetadataSecurity security, QueryAuditService auditService) {
        this.dataSourceService = dataSourceService;
        this.jdbcService = jdbcService;
        this.queryHistoryService = queryHistoryService;
        this.security = security;
        this.auditService = auditService;
    }
    public AdhocQueryController(DataSourceService dataSourceService, JdbcService jdbcService,
                                QueryHistoryService queryHistoryService, MetadataSecurity security) {
        this(dataSourceService, jdbcService, queryHistoryService, security, null);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> query(
            HttpServletRequest request,
            @Valid @RequestBody AdhocQueryRequest queryRequest) {
        AetherisIdentity identity = security.require(request, "datasource", queryRequest.datasourceId(),
                "query", Map.of());
        String username = identity.username();
        DataSource dataSource = dataSourceService.getById(queryRequest.datasourceId());
        if (dataSource == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "数据源不存在"));
        }

        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = new java.util.LinkedHashMap<>(jdbcService.query(dataSource, queryRequest.sql()));
            result.put("durationMs", System.currentTimeMillis() - startedAt);
            String requestId = QueryRequestContext.requestId(request, identity);
            if (requestId != null) result.put("requestId", requestId);
            recordHistory(request, identity, dataSource, queryRequest.sql(), "success",
                    asLong(result.get("rowCount")), (Long) result.get("durationMs"), null);
            audit(username, dataSource, queryRequest.sql(), "success", asLong(result.get("rowCount")), (Long) result.get("durationMs"), null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException error) {
            recordHistory(request, identity, dataSource, queryRequest.sql(), "failed",
                    null, System.currentTimeMillis() - startedAt, "SQL_POLICY_REJECTED");
            audit(username, dataSource, queryRequest.sql(), "failed", null, System.currentTimeMillis() - startedAt, "SQL_POLICY_REJECTED");
            return ResponseEntity.badRequest().body(failure(request, identity, "SQL_POLICY_REJECTED"));
        } catch (IllegalStateException error) {
            log.warn("Adhoc query failed: datasourceId={}, category={}", queryRequest.datasourceId(), error.getClass().getSimpleName());
            recordHistory(request, identity, dataSource, queryRequest.sql(), "failed",
                    null, System.currentTimeMillis() - startedAt, "QUERY_UNAVAILABLE");
            audit(username, dataSource, queryRequest.sql(), "failed", null, System.currentTimeMillis() - startedAt, "QUERY_UNAVAILABLE");
            return ResponseEntity.status(502).body(failure(request, identity, "QUERY_UNAVAILABLE"));
        } catch (RuntimeException error) {
            log.warn("Adhoc query failed: datasourceId={}, category={}", queryRequest.datasourceId(), error.getClass().getSimpleName());
            recordHistory(request, identity, dataSource, queryRequest.sql(), "failed", null,
                    System.currentTimeMillis() - startedAt, "QUERY_FAILED");
            audit(username, dataSource, queryRequest.sql(), "failed", null, System.currentTimeMillis() - startedAt, "QUERY_FAILED");
            return ResponseEntity.internalServerError().body(failure(request, identity, "QUERY_FAILED"));
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 历史记录是旁路能力：落库失败只记日志，不影响查询本身。 */
    private void recordHistory(HttpServletRequest request, AetherisIdentity identity, DataSource dataSource,
                               String sql, String status, Long rowCount, Long durationMs, String errorCode) {
        try {
            QueryHistory history = new QueryHistory();
            history.setUsername(identity.username());
            history.setOwnerUid(identity.uid());
            history.setRequestId(QueryRequestContext.requestId(request, identity));
            history.setDatasourceId(dataSource.getId());
            history.setDatasourceName(dataSource.getName());
            history.setSqlText(sql);
            history.setStatus(status);
            history.setRowCount(rowCount);
            history.setDurationMs(durationMs);
            history.setErrorCode(errorCode);
            history.setErrorMessage(com.aetheris.metadata.dto.QueryHistoryResponse.message(errorCode));
            queryHistoryService.record(history);
        } catch (Exception error) {
            log.warn("Failed to persist query history: datasourceId={}, category={}", dataSource.getId(), error.getClass().getSimpleName());
        }
    }

    private void audit(String username, DataSource source, String sql, String status, Long rows, Long duration, String error) {
        try { if (auditService != null) auditService.record("adhoc", null, null, source.getId(), username, status, rows, duration, false, error, null, sql); }
        catch (Exception ignored) { log.warn("Failed to persist query audit for {}", username); }
    }

    private Map<String, Object> failure(HttpServletRequest request, AetherisIdentity identity, String code) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("message", com.aetheris.metadata.dto.QueryHistoryResponse.message(code));
        result.put("code", code);
        String requestId = QueryRequestContext.requestId(request, identity);
        if (requestId != null) result.put("requestId", requestId);
        return result;
    }
}
