package com.aetheris.metadata.service;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按库名与表名推断数仓层级。
 *
 * <p>推断优先用库名（ods/dwd/dws/ads/dim），库名匹配不上时退回表名前缀，
 * 以覆盖所有表平铺在同一个库（如 Hive 的 default）的情况。两者都匹配不上时归为 other，
 * 避免层级为空导致前端筛选出现空洞。
 *
 * <p>层级只在 {@code layer_source=auto} 时被采集覆盖；人工指定过的记录不会被推断结果冲掉，
 * 与 {@code source} 字段区分手工登记与自动采集的取舍逻辑一致。
 */
public final class WarehouseLayerResolver {

    public static final String LAYER_OTHER = "other";

    /** 按顺序匹配，前缀更长的要放在前面，避免 dws 被 dw 之类的短前缀截断。 */
    private static final List<String> KNOWN_LAYERS = List.of("ods", "dwd", "dws", "ads", "dim");

    private WarehouseLayerResolver() {
    }

    /**
     * 推断层级。
     *
     * @param schemaName 库/Schema 名，可为空
     * @param tableName  表名，可为空
     * @return ods/dwd/dws/ads/dim 之一，或 other
     */
    public static String resolve(String schemaName, String tableName) {
        String fromSchema = matchLayer(schemaName);
        if (fromSchema != null) {
            return fromSchema;
        }
        String fromTable = matchLayer(tableName);
        return fromTable != null ? fromTable : LAYER_OTHER;
    }

    /** 层级名与后续内容之间允许的分隔符，Hive 的库目录名会带 .db 后缀。 */
    private static final Set<Character> SEPARATORS = Set.of('_', '-', '.');

    /**
     * 名称等于层级名，或以「层级名 + 分隔符」开头时命中（ods、ods_order、ods.db、ods-tmp）。
     *
     * <p>要求分隔符而非裸前缀匹配：否则 adsense_log 会被误判为 ads 层、
     * odyssey 之类的表名也会被前缀误伤。宁可落到 other 由人工修正，也不要给出错误层级。
     */
    private static String matchLayer(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (String layer : KNOWN_LAYERS) {
            if (normalized.equals(layer)) {
                return layer;
            }
            if (normalized.length() > layer.length()
                    && normalized.startsWith(layer)
                    && SEPARATORS.contains(normalized.charAt(layer.length()))) {
                return layer;
            }
        }
        return null;
    }
}
