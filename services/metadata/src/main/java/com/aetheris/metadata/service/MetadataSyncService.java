package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.DataSourceSchemaDto;
import com.aetheris.metadata.dto.DataSourceTableDto;
import com.aetheris.metadata.dto.MetadataSyncResult;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从数据源反向采集库表元数据，写入 metadata_table。
 *
 * <p>只读取目标库的元数据，不执行任何写操作。手工登记（source=manual）的记录不会被覆盖，
 * 采集期间目标库中已消失的表标记为 stale 而非物理删除，避免误删人工维护的信息。
 */
@Service
public class MetadataSyncService {

    private static final Logger log = LoggerFactory.getLogger(MetadataSyncService.class);
    private static final int MAX_SCHEMAS_PER_RUN = 50;
    private static final String SOURCE_AUTO = "auto";
    private static final String SOURCE_MANUAL = "manual";
    private static final String LAYER_SOURCE_AUTO = "auto";
    private static final String LAYER_SOURCE_MANUAL = "manual";

    private final JdbcService jdbcService;
    private final MetadataTableService metadataTableService;

    public MetadataSyncService(JdbcService jdbcService, MetadataTableService metadataTableService) {
        this.jdbcService = jdbcService;
        this.metadataTableService = metadataTableService;
    }

    /**
     * 采集指定数据源的元数据。
     *
     * @param dataSource 目标数据源
     * @param schemaName 指定库名；为空时采集该数据源下全部非系统库
     */
    @Transactional
    public MetadataSyncResult sync(DataSource dataSource, String schemaName) {
        List<String> schemas = resolveSchemas(dataSource, schemaName);
        List<String> failures = new ArrayList<>();
        Map<String, MetadataTable> existing = indexExisting(dataSource.getName());
        Set<String> seen = new HashSet<>();
        int scanned = 0;
        int created = 0;
        int updated = 0;
        int skipped = 0;

        for (String schema : schemas) {
            List<DataSourceTableDto> tables;
            try {
                tables = jdbcService.listTables(dataSource, schema);
            } catch (RuntimeException error) {
                log.warn("Metadata sync failed for schema {}", schema, error);
                failures.add(schema + "：" + error.getMessage());
                continue;
            }

            for (DataSourceTableDto table : tables) {
                if (!StringUtils.hasText(table.name())) {
                    continue;
                }
                scanned++;
                String key = identity(schema, table.name());
                seen.add(key);
                MetadataTable current = existing.get(key);

                if (current == null) {
                    metadataTableService.save(newRecord(dataSource, schema, table));
                    created++;
                } else if (SOURCE_MANUAL.equals(current.getSource())) {
                    skipped++;
                } else {
                    metadataTableService.updateById(mergeRecord(current, dataSource, schema, table));
                    updated++;
                }
            }
        }

        int removed = failures.isEmpty() ? markStale(existing, seen) : 0;
        return new MetadataSyncResult(
                dataSource.getName(), schemas, scanned, created, updated, removed, skipped, failures);
    }

    private List<String> resolveSchemas(DataSource dataSource, String schemaName) {
        if (StringUtils.hasText(schemaName)) {
            return List.of(schemaName.trim());
        }
        List<String> discovered = jdbcService.listSchemas(dataSource).stream()
                .map(DataSourceSchemaDto::name)
                .filter(StringUtils::hasText)
                .limit(MAX_SCHEMAS_PER_RUN)
                .toList();
        if (discovered.isEmpty()) {
            throw new IllegalStateException("目标数据源未发现可采集的库/Schema");
        }
        return discovered;
    }

    private Map<String, MetadataTable> indexExisting(String datasourceName) {
        Map<String, MetadataTable> index = new HashMap<>();
        for (MetadataTable table : metadataTableService.list(
                new LambdaQueryWrapper<MetadataTable>()
                        .eq(MetadataTable::getDatasourceName, datasourceName))) {
            index.put(identity(table.getSchemaName(), table.getTableName()), table);
        }
        return index;
    }

    /** 目标库中已不存在的自动采集记录标记为 stale，保留手工登记。 */
    private int markStale(Map<String, MetadataTable> existing, Set<String> seen) {
        int removed = 0;
        for (Map.Entry<String, MetadataTable> entry : existing.entrySet()) {
            MetadataTable table = entry.getValue();
            if (seen.contains(entry.getKey())
                    || SOURCE_MANUAL.equals(table.getSource())
                    || "stale".equals(table.getStatus())) {
                continue;
            }
            table.setStatus("stale");
            table.setUpdatedAt(OffsetDateTime.now());
            metadataTableService.updateById(table);
            removed++;
        }
        return removed;
    }

    private MetadataTable newRecord(DataSource dataSource, String schema, DataSourceTableDto table) {
        MetadataTable record = new MetadataTable();
        record.setDatasourceId(dataSource.getId());
        record.setDatasourceName(dataSource.getName());
        record.setSchemaName(schema);
        record.setTableName(table.name());
        record.setTableComment(table.remarks());
        record.setTableType(table.type());
        record.setColumnCount(countColumns(dataSource, schema, table.name()));
        record.setSource(SOURCE_AUTO);
        record.setStatus("active");
        record.setLayer(WarehouseLayerResolver.resolve(schema, table.name()));
        record.setLayerSource(LAYER_SOURCE_AUTO);
        record.setSyncedAt(OffsetDateTime.now());
        return record;
    }

    private MetadataTable mergeRecord(
            MetadataTable current, DataSource dataSource, String schema, DataSourceTableDto table) {
        current.setDatasourceId(dataSource.getId());
        current.setTableType(table.type());
        current.setColumnCount(countColumns(dataSource, schema, table.name()));
        current.setSource(SOURCE_AUTO);
        current.setStatus("active");
        current.setSyncedAt(OffsetDateTime.now());
        current.setUpdatedAt(OffsetDateTime.now());
        // 层级被人工指定过就不再覆盖，与 source 区分手工/自动的取舍逻辑一致。
        if (!LAYER_SOURCE_MANUAL.equals(current.getLayerSource())) {
            current.setLayer(WarehouseLayerResolver.resolve(schema, table.name()));
            current.setLayerSource(LAYER_SOURCE_AUTO);
        }
        // 目标库无注释时保留既有说明，避免采集清空人工补充的内容。
        if (StringUtils.hasText(table.remarks())) {
            current.setTableComment(table.remarks());
        }
        return current;
    }

    /** 字段数属于附加信息，单表失败不应中断整轮采集。 */
    private Integer countColumns(DataSource dataSource, String schema, String table) {
        try {
            return jdbcService.listColumns(dataSource, schema, table).size();
        } catch (RuntimeException error) {
            log.debug("Column count unavailable for {}.{}", schema, table, error);
            return null;
        }
    }

    private String identity(String schema, String table) {
        return (schema == null ? "" : schema) + " " + table;
    }
}
