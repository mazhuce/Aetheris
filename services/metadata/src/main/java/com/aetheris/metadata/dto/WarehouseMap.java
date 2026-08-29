package com.aetheris.metadata.dto;

import java.util.List;

/**
 * 数仓地图：按层级聚合的表分布，用于前端「数仓地图」视图。
 *
 * @param layers 各层级的统计，按 ods → dwd → dws → ads → dim → other 排序
 * @param total  纳入统计的表总数
 */
public record WarehouseMap(List<LayerSummary> layers, int total) {

    /**
     * 单个层级的统计。
     *
     * @param layer      层级标识：ods/dwd/dws/ads/dim/other
     * @param label      层级中文名，供前端直接展示
     * @param tableCount 该层表数
     * @param schemas    该层涉及的库名
     * @param tables     该层的表清单（含所属库与注释），供前端展开查看
     */
    public record LayerSummary(
            String layer,
            String label,
            int tableCount,
            List<String> schemas,
            List<TableBrief> tables) {
    }

    /**
     * 表的简要信息，仅含数仓地图需要展示的字段。
     *
     * @param id           元数据表主键，供前端跳转到详情
     * @param schemaName   所属库
     * @param tableName    表名
     * @param tableComment 表注释
     * @param columnCount  字段数
     * @param status       状态：active 或 stale
     */
    public record TableBrief(
            Long id,
            String schemaName,
            String tableName,
            String tableComment,
            Integer columnCount,
            String status) {
    }
}
