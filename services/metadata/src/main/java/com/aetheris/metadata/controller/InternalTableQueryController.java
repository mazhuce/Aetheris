package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.DataSourceColumnDto;
import com.aetheris.metadata.dto.TableQueryRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.MetadataTableService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Internal table API used by the serving facade; all SQL is compiled here. */
@RestController
@RequestMapping("/internal/tables")
public class InternalTableQueryController {
    private final MetadataTableService tables;
    private final DataSourceService dataSources;
    private final JdbcService jdbc;

    public InternalTableQueryController(MetadataTableService tables, DataSourceService dataSources, JdbcService jdbc) {
        this.tables = tables;
        this.dataSources = dataSources;
        this.jdbc = jdbc;
    }

    @PostMapping("/{id}/query")
    public ResponseEntity<Map<String, Object>> query(@PathVariable Long id,
                                                       @Valid @RequestBody(required = false) TableQueryRequest request) {
        MetadataTable table = tables.getById(id);
        if (table == null || "stale".equalsIgnoreCase(table.getStatus())) {
            return ResponseEntity.notFound().build();
        }
        DataSource source = table.getDatasourceId() == null ? null : dataSources.getById(table.getDatasourceId());
        if (source == null) return ResponseEntity.badRequest().body(Map.of("message", "表所属数据源不可用"));

        TableQueryRequest actual = request == null ? new TableQueryRequest(List.of(), 100) : request;
        List<DataSourceColumnDto> known = jdbc.listColumns(source, table.getSchemaName(), table.getTableName());
        Map<String, String> columns = new HashMap<>();
        for (DataSourceColumnDto column : known) columns.put(column.name().toLowerCase(Locale.ROOT), column.name());
        List<String> selected = actual.columns().isEmpty() ? known.stream().limit(50).map(DataSourceColumnDto::name).toList()
                : validateColumns(actual.columns(), columns);
        if (selected.isEmpty()) throw new IllegalArgumentException("表没有可查询字段");
        String projection = selected.stream().map(value -> quote(value, source)).reduce((left, right) -> left + ", " + right).orElseThrow();
        String tableName = (table.getSchemaName() == null || table.getSchemaName().isBlank() ? "" : quote(table.getSchemaName(), source) + ".") + quote(table.getTableName(), source);
        int limit = Math.clamp(actual.limit() == null ? 100 : actual.limit(), 1, 100);
        return ResponseEntity.ok(jdbc.query(source, "SELECT " + projection + " FROM " + tableName + " LIMIT " + limit));
    }

    private List<String> validateColumns(List<String> requested, Map<String, String> known) {
        if (requested.size() > 50) throw new IllegalArgumentException("单次最多选择 50 个字段");
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : requested) {
            if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_$]*")) throw new IllegalArgumentException("字段名包含不支持的字符");
            String actual = known.get(value.toLowerCase(Locale.ROOT));
            if (actual == null || !seen.add(actual.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("字段不存在或重复：" + value);
            result.add(actual);
        }
        return result;
    }

    private String quote(String value, DataSource source) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_$]*")) throw new IllegalArgumentException("表或字段名包含不支持的字符");
        String type = source.getType() == null ? "" : source.getType().toLowerCase(Locale.ROOT);
        String marker = type.contains("postgres") || type.contains("greenplum") || type.contains("opengauss") ? "\"" : "`";
        return marker + value + marker;
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
