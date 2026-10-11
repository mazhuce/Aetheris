package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.DataSourceColumnDto;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.MetadataTableService;
import com.aetheris.metadata.service.QueryAuditService;
import com.aetheris.metadata.security.MetadataSecurity;
import jakarta.servlet.http.HttpServletRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 供 DataAgent 使用的元数据工具端点。
 *
 * <p>为什么单独一组端点而不复用 {@code /api/metadata/**}：agent 的工具调用需要为 LLM 优化的
 * 返回结构（精简字段、注释优先、结果带引导语），与前端表格需要的结构不同。混用会让两边互相牵制。
 *
 * <p>安全边界：
 * <ul>
 *   <li>路径以 {@code /internal} 开头，网关只路由 {@code /api/**}，外部不可达；</li>
 *   <li>查询走 {@code JdbcService.query}，其 {@code validateReadOnlySql} 拒绝一切写入语句；</li>
 *   <li>agent 使用的数据源应配置为只读账号，即便守卫被绕过也无写权限（读写分离的兜底）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/agent")
public class InternalAgentToolController {

    private static final Logger log = LoggerFactory.getLogger(InternalAgentToolController.class);
    private static final int MAX_SEARCH_RESULTS = 15;

    private final MetadataTableService metadataTableService;
    private final DataSourceService dataSourceService;
    private final JdbcService jdbcService;
    private final MetadataSecurity security;
    private final QueryAuditService auditService;

    public InternalAgentToolController(
            MetadataTableService metadataTableService,
            DataSourceService dataSourceService,
            JdbcService jdbcService,
            MetadataSecurity security,
            QueryAuditService auditService) {
        this.metadataTableService = metadataTableService;
        this.dataSourceService = dataSourceService;
        this.jdbcService = jdbcService;
        this.security = security;
        this.auditService = auditService;
    }

    /**
     * 按关键词检索表。
     *
     * <p>同时匹配表名、表注释与业务域：用户问「订单」时，表名含 order 的和注释含「订单」的都该命中。
     * 当前表数量在数十量级，关键词检索足够；表数上到几百后再引入向量检索。
     */
    @GetMapping("/tables/search")
    public List<Map<String, Object>> searchTables(
            HttpServletRequest httpRequest,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String layer) {
        String username = security.requireIdentity(httpRequest).username();
        LambdaQueryWrapper<MetadataTable> query = new LambdaQueryWrapper<>();
        // 已消失的表不该出现在 agent 的候选里，否则它会对着不存在的表写 SQL。
        query.ne(MetadataTable::getStatus, "stale");
        if (StringUtils.hasText(layer)) {
            query.eq(MetadataTable::getLayer, layer.trim().toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(keyword)) {
            String key = keyword.trim();
            query.and(w -> w.like(MetadataTable::getTableName, key)
                    .or().like(MetadataTable::getTableComment, key)
                    .or().like(MetadataTable::getDomain, key));
        }
        // 按层级排序让 ADS/DWS 这类「已加工好的结果表」优先被看到，
        // 减少 agent 直接去啃 ODS 原始脏数据的概率。
        query.orderByAsc(MetadataTable::getLayer).orderByAsc(MetadataTable::getTableName);

        List<MetadataTable> tables = metadataTableService.list(query);
        MetadataSecurity.Scope scope = security.visibility(httpRequest, "table", "view");
        tables = switch (scope.mode()) {
            case ALL -> tables;
            case IDS -> tables.stream().filter(table -> table.getBusinessDomainId() != null
                    && scope.ids().contains(table.getBusinessDomainId())).toList();
            case OPAQUE -> tables.stream().filter(table -> security.isAllowed(username, "table", table.getId(),
                    "view", table.getBusinessDomainId() == null ? Map.of()
                            : Map.of("domainId", String.valueOf(table.getBusinessDomainId())))).toList();
        };
        List<Map<String, Object>> results = new ArrayList<>();
        for (MetadataTable table : tables.stream().limit(MAX_SEARCH_RESULTS).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", table.getId());
            item.put("fullName", qualifiedName(table));
            item.put("layer", table.getLayer());
            item.put("comment", table.getTableComment());
            item.put("columnCount", table.getColumnCount());
            item.put("datasourceId", table.getDatasourceId());
            results.add(item);
        }
        return results;
    }

    /**
     * 读取表结构（字段名、类型、注释）。
     *
     * <p>字段注释是 NL2SQL 准确率的关键：没有注释，LLM 只能靠字段名猜测语义。
     */
    @GetMapping("/tables/describe")
    public ResponseEntity<?> describeTable(HttpServletRequest httpRequest, @RequestParam Long tableId) {
        MetadataTable table = metadataTableService.getById(tableId);
        if (table == null) {
            return ResponseEntity.ok(Map.of("error", "表不存在，请先用 search_tables 检索"));
        }
        security.require(httpRequest, "table", tableId, "view", table.getBusinessDomainId() == null ? Map.of()
                : Map.of("domainId", String.valueOf(table.getBusinessDomainId())));
        DataSource dataSource = table.getDatasourceId() == null
                ? null
                : dataSourceService.getById(table.getDatasourceId());
        if (dataSource == null) {
            return ResponseEntity.ok(Map.of("error", "该表所属数据源已不可用"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fullName", qualifiedName(table));
        result.put("comment", table.getTableComment());
        result.put("layer", table.getLayer());
        result.put("datasourceId", table.getDatasourceId());

        try {
            List<DataSourceColumnDto> columns =
                    jdbcService.listColumns(dataSource, table.getSchemaName(), table.getTableName());
            List<Map<String, Object>> columnInfo = new ArrayList<>(columns.size());
            for (DataSourceColumnDto column : columns) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", column.name());
                item.put("type", column.type());
                item.put("comment", column.remarks());
                columnInfo.add(item);
            }
            result.put("columns", columnInfo);
        } catch (RuntimeException error) {
            log.warn("Describe table failed for {}", qualifiedName(table), error);
            result.put("error", "无法读取字段信息：" + error.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 执行只读查询。
     *
     * <p>沿用 {@code JdbcService.query} 的 {@code validateReadOnlySql}：只放行
     * SELECT / SHOW / DESCRIBE / EXPLAIN，并拒绝可读服务器文件或阻塞连接的函数。
     * 这里不放宽任何限制——agent 生成的 SQL 与人工输入的查询受同一套约束。
     */
    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(HttpServletRequest httpRequest,
                                                     @RequestBody Map<String, Object> request) {
        Object datasourceId = request.get("datasourceId");
        Object sql = request.get("sql");
        if (!(datasourceId instanceof Number id) || !(sql instanceof String statement)) {
            return ResponseEntity.ok(Map.of("error", "缺少 datasourceId 或 sql"));
        }

        DataSource dataSource = dataSourceService.getById(id.longValue());
        if (dataSource == null) {
            return ResponseEntity.ok(Map.of("error", "数据源不存在"));
        }

        String username = security.require(httpRequest, "datasource", dataSource.getId(), "query", Map.of()).username();
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = jdbcService.query(dataSource, statement);
            audit(username, dataSource, statement, "success", asLong(result.get("rowCount")),
                    System.currentTimeMillis() - startedAt, null);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException error) {
            // 语句被守卫拒绝：把原因返回给 agent，让它自己改写重试，而不是直接失败。
            audit(username, dataSource, statement, "failed", null,
                    System.currentTimeMillis() - startedAt, error.getMessage());
            return ResponseEntity.ok(Map.of("error", "SQL 不被允许：" + error.getMessage()));
        } catch (IllegalStateException error) {
            log.warn("Agent query failed on datasource {}", id, error);
            // 执行错误（语法、表不存在）同样返回给 agent 用于自我修正。
            audit(username, dataSource, statement, "failed", null,
                    System.currentTimeMillis() - startedAt, error.getMessage());
            return ResponseEntity.ok(Map.of("error", "查询失败：" + error.getMessage()));
        }
    }

    private void audit(String username, DataSource dataSource, String sql, String status,
                       Long rows, Long duration, String error) {
        try {
            auditService.record("agent", null, null, dataSource.getId(), username, status,
                    rows, duration, false, error == null ? null : "QUERY_FAILED",
                    error, sql);
        } catch (RuntimeException auditError) {
            // 审计是旁路记录，不能把一个已经完成的只读查询变成失败。
            log.warn("Failed to persist agent query audit for {}", username, auditError);
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String qualifiedName(MetadataTable table) {
        return StringUtils.hasText(table.getSchemaName())
                ? table.getSchemaName() + "." + table.getTableName()
                : table.getTableName();
    }
}
