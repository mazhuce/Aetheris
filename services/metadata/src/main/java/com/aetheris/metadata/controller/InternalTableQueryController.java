package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.DataSourceColumnDto;
import com.aetheris.metadata.dto.TableQueryRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.MetadataTableService;
import com.aetheris.metadata.service.QueryAuditService;
import com.aetheris.metadata.service.TableDataQueryService;
import com.aetheris.metadata.security.MetadataSecurity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Internal table API used by the serving facade; all SQL is compiled here. */
@RestController
@RequestMapping("/internal/tables")
public class InternalTableQueryController {
    private DomainPermissionContext permissionContext;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPermissionContext(DomainPermissionContext context) { this.permissionContext = context; }
    private Map<String,String> domainCtx(Long id) {
        return permissionContext == null ? (id == null ? Map.of() : Map.of("domainId",id.toString())) : permissionContext.of(id);
    }
    private final MetadataTableService tables;
    private final DataSourceService dataSources;
    private final JdbcService jdbc;
    private final MetadataSecurity security;
    private final QueryAuditService auditService;
    private final TableDataQueryService queries;

    public InternalTableQueryController(MetadataTableService tables, DataSourceService dataSources, JdbcService jdbc,
                                        MetadataSecurity security, QueryAuditService auditService) {
        this.tables = tables;
        this.dataSources = dataSources;
        this.jdbc = jdbc;
        this.security = security;
        this.auditService = auditService;
        this.queries = new TableDataQueryService(dataSources,jdbc);
    }

    @PostMapping("/{id}/query")
    public ResponseEntity<Map<String, Object>> query(HttpServletRequest httpRequest, @PathVariable Long id,
                                                       @Valid @RequestBody(required = false) TableQueryRequest request) {
        MetadataTable table = tables.getById(id);
        if (table == null || "stale".equalsIgnoreCase(table.getStatus())) {
            return ResponseEntity.notFound().build();
        }
        String username = security.require(httpRequest, "table", id, "query",
                domainCtx(table.getBusinessDomainId()))
                .username();
        var plan=queries.prepare(table,request);
        DataSource source=plan.source();String sql=plan.sql();
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = queries.execute(plan);
            audit(username, table, source, sql, "success", result, null, startedAt);
            return ResponseEntity.ok(result);
        } catch (RuntimeException error) {
            audit(username, table, source, sql, "failed", null, error, startedAt);
            throw error;
        }
    }

    private void audit(String username, MetadataTable table, DataSource source, String sql, String status,
                       Map<String, Object> result, RuntimeException error, long startedAt) {
        try {
            Long rows = result != null && result.get("rowCount") instanceof Number number ? number.longValue() : null;
            auditService.record("table", null, table.getId(), source.getId(), username, status, rows,
                    System.currentTimeMillis() - startedAt, false,
                    error == null ? null : "QUERY_FAILED",
                    error == null ? null : error.getMessage(), sql);
        } catch (RuntimeException ignored) {
            // 审计旁路失败不能改变查询结果。
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage() == null ? "请求参数无效" : error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> queryFailure() {
        return ResponseEntity.status(502).body(Map.of("message", "表查询失败，请检查数据源连通性"));
    }
}
