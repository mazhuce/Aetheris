package com.aetheris.metadata.dto;

import java.util.List;

/**
 * 一次元数据采集的结果统计。
 *
 * @param datasourceName 采集的数据源名称
 * @param schemas        实际采集的库/Schema 列表
 * @param scanned        目标库中发现的表总数
 * @param created        新增登记的表数
 * @param updated        更新的既有表数
 * @param removed        标记为已消失（stale）的表数
 * @param skipped        因手工登记而跳过的表数
 * @param failures       采集失败的库及原因
 */
public record MetadataSyncResult(
        String datasourceName,
        List<String> schemas,
        int scanned,
        int created,
        int updated,
        int removed,
        int skipped,
        List<String> failures) {
}
