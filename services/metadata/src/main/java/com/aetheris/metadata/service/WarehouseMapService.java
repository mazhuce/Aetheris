package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.WarehouseMap;
import com.aetheris.metadata.entity.MetadataTable;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组装数仓地图：把 metadata_table 按层级聚合成前端可直接渲染的结构。
 *
 * <p>层级顺序固定为 ods → dwd → dws → ads → dim → other，与数据流向一致；
 * 空层级也会返回（tableCount=0），这样前端画分层图时不会因为某层暂时没表而错位。
 */
@Service
public class WarehouseMapService {

    /** 层级展示顺序与中文名，顺序即数据流向。 */
    private static final Map<String, String> LAYER_LABELS = new LinkedHashMap<>();

    static {
        LAYER_LABELS.put("ods", "贴源层");
        LAYER_LABELS.put("dwd", "明细层");
        LAYER_LABELS.put("dws", "汇总层");
        LAYER_LABELS.put("ads", "应用层");
        LAYER_LABELS.put("dim", "维度层");
        LAYER_LABELS.put("other", "未分层");
    }

    private final MetadataTableService metadataTableService;

    public WarehouseMapService(MetadataTableService metadataTableService) {
        this.metadataTableService = metadataTableService;
    }

    /**
     * 按数据源组装数仓地图。
     *
     * @param datasourceName 数据源名称；为空时统计全部数据源
     * @param includeStale   是否纳入已消失（stale）的表
     */
    public WarehouseMap build(String datasourceName, boolean includeStale) {
        LambdaQueryWrapper<MetadataTable> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(datasourceName)) {
            query.eq(MetadataTable::getDatasourceName, datasourceName.trim());
        }
        if (!includeStale) {
            query.ne(MetadataTable::getStatus, "stale");
        }
        query.orderByAsc(MetadataTable::getSchemaName).orderByAsc(MetadataTable::getTableName);

        List<MetadataTable> tables = metadataTableService.list(query);

        // 预置全部层级，保证空层级也出现在结果里。
        Map<String, List<MetadataTable>> grouped = new LinkedHashMap<>();
        LAYER_LABELS.keySet().forEach(layer -> grouped.put(layer, new ArrayList<>()));

        for (MetadataTable table : tables) {
            // layer 为空的历史记录（V7 迁移前写入）归入 other，避免在地图上凭空消失。
            String layer = StringUtils.hasText(table.getLayer())
                    ? table.getLayer()
                    : WarehouseLayerResolver.LAYER_OTHER;
            grouped.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(table);
        }

        List<WarehouseMap.LayerSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<MetadataTable>> entry : grouped.entrySet()) {
            String layer = entry.getKey();
            List<MetadataTable> layerTables = entry.getValue();

            Set<String> schemas = new LinkedHashSet<>();
            List<WarehouseMap.TableBrief> briefs = new ArrayList<>(layerTables.size());
            for (MetadataTable table : layerTables) {
                if (StringUtils.hasText(table.getSchemaName())) {
                    schemas.add(table.getSchemaName());
                }
                briefs.add(new WarehouseMap.TableBrief(
                        table.getId(),
                        table.getSchemaName(),
                        table.getTableName(),
                        table.getTableComment(),
                        table.getColumnCount(),
                        table.getStatus()));
            }

            summaries.add(new WarehouseMap.LayerSummary(
                    layer,
                    LAYER_LABELS.getOrDefault(layer, layer),
                    layerTables.size(),
                    List.copyOf(schemas),
                    briefs));
        }

        // 兜底：若出现 LAYER_LABELS 未覆盖的层级（例如人工写入了新值），排到末尾而非丢弃。
        summaries.sort(Comparator.comparingInt(s -> {
            int index = new ArrayList<>(LAYER_LABELS.keySet()).indexOf(s.layer());
            return index < 0 ? Integer.MAX_VALUE : index;
        }));

        return new WarehouseMap(summaries, tables.size());
    }
}
