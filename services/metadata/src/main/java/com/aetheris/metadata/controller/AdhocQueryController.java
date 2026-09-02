package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.AdhocQueryRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.QueryHistory;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.QueryHistoryService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 */
@RestController
@RequestMapping("/api/metadata/query")
public class AdhocQueryController {

    private static final Logger log = LoggerFactory.getLogger(AdhocQueryController.class);

    private final DataSourceService dataSourceService;
    private final JdbcService jdbcService;
    private final QueryHistoryService queryHistoryService;

    public AdhocQueryController(DataSourceService dataSourceService, JdbcService jdbcService,
                                QueryHistoryService queryHistoryService) {
        this.dataSourceService = dataSourceService;
        this.jdbcService = jdbcService;
        this.queryHistoryService = queryHistoryService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> query(
            @Valid @RequestBody AdhocQueryRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username) {
        DataSource dataSource = dataSourceService.getById(request.datasourceId());
        if (dataSource == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "数据源不存在"));
        }

        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = jdbcService.query(dataSource, request.sql());
            result.put("durationMs", System.currentTimeMillis() - startedAt);
            recordHistory(username, dataSource, request.sql(), "success",
                    asLong(result.get("rowCount")), (Long) result.get("durationMs"), null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException error) {
            // 语句被只读守卫拒绝：原因可安全回显。
            recordHistory(username, dataSource, request.sql(), "failed",
                    null, System.currentTimeMillis() - startedAt, error.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            log.warn("Adhoc query by {} on datasource {} failed", username, request.datasourceId(), error);
            recordHistory(username, dataSource, request.sql(), "failed",
                    null, System.currentTimeMillis() - startedAt, error.getMessage());
            return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 历史记录是旁路能力：落库失败只记日志，不影响查询本身。 */
    private void recordHistory(String username, DataSource dataSource, String sql, String status,
                               Long rowCount, Long durationMs, String errorMessage) {
        try {
            QueryHistory history = new QueryHistory();
            history.setUsername(username);
            history.setDatasourceId(dataSource.getId());
            history.setDatasourceName(dataSource.getName());
            history.setSqlText(sql);
            history.setStatus(status);
            history.setRowCount(rowCount);
            history.setDurationMs(durationMs);
            history.setErrorMessage(errorMessage);
            queryHistoryService.record(history);
        } catch (Exception error) {
            log.warn("Failed to persist query history for {}: {}", username, error.getMessage());
        }
    }
}
