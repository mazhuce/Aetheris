package com.aetheris.metadata.dto;

import java.util.List;

/**
 * 一次 SQL 血缘解析的结果统计。
 *
 * @param statements 识别出目标表的语句数
 * @param created    新写入的血缘边数
 * @param replaced   因重新解析而删除的旧边数
 * @param skipped    因已存在人工登记的同名边而跳过的数量
 * @param edges      新写入的边，形如 "ods.ods_order → dwd.dwd_order"，供界面回显
 */
public record LineageParseResult(
        int statements,
        int created,
        int replaced,
        int skipped,
        List<String> edges) {
}
