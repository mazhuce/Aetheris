package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.QueryHistory;
import com.aetheris.metadata.dto.DataSourceResponse;
import com.aetheris.metadata.security.AetherisIdentity;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.DataSourceHealthService;
import com.aetheris.metadata.service.DataSourceTypeCatalog;
import com.aetheris.metadata.service.DataSourceConnectionInputValidator;
import com.aetheris.metadata.service.QueryHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    public List<DataSourceResponse> list(HttpServletRequest request) {
        String username = security.requireIdentity(request).username();
        List<DataSource> sources = dataSourceService.list();
        java.util.Set<Long> allowed = new java.util.HashSet<>();
        for (int offset=0; offset<sources.size(); offset+=500) {
            Map<Long,Map<String,String>> contexts = sources.subList(offset, Math.min(offset+500,sources.size())).stream()
                    .collect(java.util.stream.Collectors.toMap(DataSource::getId, value -> Map.of(), (left,right)->left));
            allowed.addAll(security.allowedIds(username,"datasource","view",contexts));
        }
        return healthService.attach(sources.stream().filter(source -> allowed.contains(source.getId())).toList())
                .stream().map(DataSourceResponse::from).toList();
    }

    @GetMapping("/catalog")
    public List<DataSourceTypeCatalog.Definition> catalog(HttpServletRequest request) {
        security.requireIdentity(request);
        return DataSourceTypeCatalog.all();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataSourceResponse> getById(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "datasource", id, "view", Map.of());
        DataSource dataSource = dataSourceService.getById(id);
        return dataSource != null
                ? ResponseEntity.ok(DataSourceResponse.from(healthService.attach(dataSource)))
                : ResponseEntity.notFound().build();
    }

    @PostMapping
    public DataSourceResponse create(HttpServletRequest request, @Valid @RequestBody DataSource dataSource) {
        security.require(request, "datasource", null, "create", Map.of());
        DataSourceTypeCatalog.require(dataSource.getType());
        DataSourceConnectionInputValidator.validateCreate(dataSource);
        dataSource.setManaged(false);
        dataSourceService.save(dataSource);
        return DataSourceResponse.from(dataSource);
    }

    @PostMapping("/test")
    public Map<String, Object> testBeforeSave(HttpServletRequest request, @Valid @RequestBody DataSource dataSource) {
        security.require(request, "datasource", null, "create", Map.of());
        DataSourceTypeCatalog.require(dataSource.getType());
        DataSourceConnectionInputValidator.validateCreate(dataSource);
        dataSource.setManaged(false);
        return DataSourceResponse.publicProbe(healthService.testBeforeSave(dataSource));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DataSourceResponse> update(
            HttpServletRequest request,
            @PathVariable Long id,
        @Valid @RequestBody DataSource dataSource) {
        DataSource existing = dataSourceService.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        AetherisIdentity actor = security.require(request, "datasource", id, "update", Map.of());
        if (Boolean.TRUE.equals(existing.getManaged())) {
            return ResponseEntity.status(403).build();
        }
        DataSourceTypeCatalog.require(dataSource.getType());
        DataSourceConnectionInputValidator.validateUpdate(dataSource, existing);
        dataSource.setManaged(false);
        dataSource.setId(id);
        boolean updated = dataSourceService.updateById(dataSource);
        if (updated && Boolean.TRUE.equals(dataSource.getDisableWriteCredentials()))
            log.info("Datasource write credentials disabled: datasourceId={}, actor={}", id, actor.username());
        return updated
                ? ResponseEntity.ok(DataSourceResponse.from(healthService.attach(dataSourceService.getById(id))))
                : ResponseEntity.status(409).build();
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
        return ResponseEntity.ok(DataSourceResponse.publicProbe(healthService.test(dataSource)));
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
            Map<String, Object> result = new java.util.LinkedHashMap<>(jdbcService.query(dataSource, sql));
            long durationMs = System.currentTimeMillis() - startedAt;
            result.put("durationMs", durationMs);
            String requestId = com.aetheris.metadata.security.QueryRequestContext.requestId(request, identity);
            if (requestId != null) result.put("requestId", requestId);
            recordHistory(request, identity, dataSource, sql, "success",
                    asLong(result.get("rowCount")), durationMs, null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            recordHistory(request, identity, dataSource, sql, "failed",
                    null, System.currentTimeMillis() - startedAt, "SQL_POLICY_REJECTED");
            return ResponseEntity.badRequest().body(queryFailure(request, identity, "SQL_POLICY_REJECTED"));
        } catch (IllegalStateException e) {
            log.warn("Datasource query failed: datasourceId={}, category={}", id, e.getClass().getSimpleName());
            recordHistory(request, identity, dataSource, sql, "failed",
                    null, System.currentTimeMillis() - startedAt, "QUERY_UNAVAILABLE");
            return ResponseEntity.status(502).body(queryFailure(request, identity, "QUERY_UNAVAILABLE"));
        } catch (Exception e) {
            log.warn("Datasource query failed: datasourceId={}, category={}", id, e.getClass().getSimpleName());
            recordHistory(request, identity, dataSource, sql, "failed",
                    null, System.currentTimeMillis() - startedAt, "QUERY_FAILED");
            return ResponseEntity.status(500).body(queryFailure(request, identity, "QUERY_FAILED"));
        }
    }

    private Map<String, Object> queryFailure(HttpServletRequest request, AetherisIdentity identity, String code) {
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("message", com.aetheris.metadata.dto.QueryHistoryResponse.message(code)); response.put("code", code);
        String requestId = com.aetheris.metadata.security.QueryRequestContext.requestId(request, identity);
        if (requestId != null) response.put("requestId", requestId);
        return response;
    }

    private void recordHistory(HttpServletRequest request, AetherisIdentity identity, DataSource dataSource,
                               String sql, String status, Long rowCount, Long durationMs, String errorCode) {
        try {
            QueryHistory history = new QueryHistory();
            history.setUsername(identity.username());
            history.setOwnerUid(identity.uid());
            history.setRequestId(com.aetheris.metadata.security.QueryRequestContext.requestId(request, identity));
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
            log.warn("Datasource schema discovery failed: datasourceId={}, category={}", id, error.getClass().getSimpleName());
            return ResponseEntity.status(502).body(Map.of("message", "无法读取数据库结构，请检查数据源配置与权限"));
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
            return ResponseEntity.badRequest().body(Map.of("message", "请求参数不合法"));
        } catch (Exception error) {
            log.warn("Datasource table discovery failed: datasourceId={}, category={}", id, error.getClass().getSimpleName());
            return ResponseEntity.status(502).body(Map.of("message", "无法读取数据表，请检查数据源配置与权限"));
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
            return ResponseEntity.badRequest().body(Map.of("message", "请求参数不合法"));
        } catch (Exception error) {
            log.warn("Datasource column discovery failed: datasourceId={}, category={}", id, error.getClass().getSimpleName());
            return ResponseEntity.status(502).body(Map.of("message", "无法读取字段，请检查数据源配置与权限"));
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
            return ResponseEntity.badRequest().body(Map.of("message", "请求参数不合法"));
        } catch (Exception error) {
            log.warn("Datasource preview failed: datasourceId={}, category={}", id, error.getClass().getSimpleName());
            return ResponseEntity.status(502).body(Map.of("message", "预览失败，请检查数据源配置与权限"));
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,Object>> invalidConnectionInput() {
        return ResponseEntity.badRequest().body(Map.of("message", "数据源配置不合法，请检查连接地址与参数；凭据请使用独立字段"));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String,Object>> persistenceUnavailable() {
        return ResponseEntity.status(503).body(Map.of("message", "数据源配置服务暂不可用"));
    }
}
