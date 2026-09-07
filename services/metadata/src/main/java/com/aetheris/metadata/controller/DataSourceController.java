package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.QueryHistory;
import com.aetheris.metadata.security.AetherisIdentity;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.DataSourceHealthService;
import com.aetheris.metadata.service.QueryHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/datasources")
public class DataSourceController {

    private static final Logger log = LoggerFactory.getLogger(DataSourceController.class);

    private final DataSourceService dataSourceService;
    private final JdbcService jdbcService;
    private final QueryHistoryService queryHistoryService;
    private final MetadataSecurity security;
    private final DataSourceHealthService healthService;

    public DataSourceController(DataSourceService dataSourceService, JdbcService jdbcService,
                                 QueryHistoryService queryHistoryService, MetadataSecurity security,
                                 DataSourceHealthService healthService) {
        this.dataSourceService = dataSourceService;
        this.jdbcService = jdbcService;
        this.queryHistoryService = queryHistoryService;
        this.security = security;
        this.healthService = healthService;
    }

    @GetMapping
    public List<DataSource> list(HttpServletRequest request) {
        security.require(request, "datasource", null, "view", Map.of());
        return healthService.attach(dataSourceService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataSource> getById(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "datasource", id, "view", Map.of());
        DataSource dataSource = dataSourceService.getById(id);
        return dataSource != null
                ? ResponseEntity.ok(healthService.attach(dataSource))
                : ResponseEntity.notFound().build();
    }

    @PostMapping
    public DataSource create(HttpServletRequest request, @Valid @RequestBody DataSource dataSource) {
        security.require(request, "datasource", null, "create", Map.of());
        dataSource.setManaged(false);
        dataSourceService.save(dataSource);
        return dataSource;
    }

    @PostMapping("/test")
    public Map<String, Object> testBeforeSave(HttpServletRequest request, @Valid @RequestBody DataSource dataSource) {
        security.require(request, "datasource", null, "create", Map.of());
        dataSource.setManaged(false);
        return healthService.testBeforeSave(dataSource);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DataSource> update(
            HttpServletRequest request,
            @PathVariable Long id,
        @Valid @RequestBody DataSource dataSource) {
        DataSource existing = dataSourceService.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        security.require(request, "datasource", id, "update", Map.of());
        if (Boolean.TRUE.equals(existing.getManaged())) {
            return ResponseEntity.status(403).build();
        }
        if (dataSource.getPassword() == null || dataSource.getPassword().isBlank()) {
            dataSource.setPassword(existing.getPassword());
        }
        dataSource.setManaged(false);
        dataSource.setId(id);
        boolean updated = dataSourceService.updateById(dataSource);
        return updated
                ? ResponseEntity.ok(healthService.attach(dataSourceService.getById(id)))
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        DataSource existing = dataSourceService.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        security.require(request, "datasource", id, "delete", Map.of());
        if (Boolean.TRUE.equals(existing.getManaged())) {
            return ResponseEntity.status(403).build();
        }
        boolean removed = dataSourceService.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "datasource", id, "query", Map.of());
        DataSource dataSource = dataSourceService.getById(id);
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(healthService.test(dataSource));
    }

    @PostMapping("/{id}/query")
    public ResponseEntity<Map<String, Object>> query(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        AetherisIdentity identity = security.require(request, "datasource", id, "query", Map.of());
        DataSource dataSource = dataSourceService.getById(id);
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        String sql = body == null ? null : body.get("sql");
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = jdbcService.query(dataSource, sql);
            long durationMs = System.currentTimeMillis() - startedAt;
            result.put("durationMs", durationMs);
            recordHistory(identity.username(), dataSource, sql, "success",
                    asLong(result.get("rowCount")), durationMs, null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            recordHistory(identity.username(), dataSource, sql, "failed",
                    null, System.currentTimeMillis() - startedAt, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("Datasource query failed for {} on datasource {}", identity.username(), id, e);
            recordHistory(identity.username(), dataSource, sql, "failed",
                    null, System.currentTimeMillis() - startedAt, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("Datasource query failed", e);
            String message = "查询失败，请检查 SQL 与数据源配置";
            recordHistory(identity.username(), dataSource, sql, "failed",
                    null, System.currentTimeMillis() - startedAt, message);
            return ResponseEntity.status(500).body(Map.of("message", message));
        }
    }

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

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    @GetMapping("/{id}/schemas")
    public ResponseEntity<?> schemas(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "datasource", id, "view", Map.of());
        DataSource dataSource = dataSourceService.getById(id);
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(jdbcService.listSchemas(dataSource));
        } catch (Exception error) {
            log.warn("Datasource schema discovery failed", error);
            return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
        }
    }

    @GetMapping("/{id}/tables")
    public ResponseEntity<?> tables(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestParam String schema) {
        security.require(request, "datasource", id, "view", Map.of());
        DataSource dataSource = dataSourceService.getById(id);
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(jdbcService.listTables(dataSource, schema));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (Exception error) {
            log.warn("Datasource table discovery failed", error);
            return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
        }
    }

    @GetMapping("/{id}/columns")
    public ResponseEntity<?> columns(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestParam String schema,
            @RequestParam String table) {
        security.require(request, "datasource", id, "view", Map.of());
        DataSource dataSource = dataSourceService.getById(id);
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(jdbcService.listColumns(dataSource, schema, table));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (Exception error) {
            log.warn("Datasource column discovery failed", error);
            return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
        }
    }

    @PostMapping("/{id}/preview")
    public ResponseEntity<?> preview(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        security.require(request, "datasource", id, "query", Map.of());
        DataSource dataSource = dataSourceService.getById(id);
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(jdbcService.preview(dataSource, body.get("schema"), body.get("table")));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (Exception error) {
            log.warn("Datasource preview failed", error);
            return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
        }
    }
}
