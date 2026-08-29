package com.aetheris.metadata.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarehouseLayerResolverTest {

    @Test
    @DisplayName("库名恰好等于层级名时命中")
    void resolvesExactSchemaName() {
        assertEquals("ods", WarehouseLayerResolver.resolve("ods", "ods_order"));
        assertEquals("dwd", WarehouseLayerResolver.resolve("dwd", "dwd_order"));
        assertEquals("dws", WarehouseLayerResolver.resolve("dws", "dws_order_daily"));
        assertEquals("ads", WarehouseLayerResolver.resolve("ads", "ads_order_stats"));
        assertEquals("dim", WarehouseLayerResolver.resolve("dim", "dim_date"));
    }

    @Test
    @DisplayName("Hive 库目录名带 .db 后缀时命中")
    void resolvesHiveDatabaseDirectoryName() {
        assertEquals("ods", WarehouseLayerResolver.resolve("ods.db", "ods_order"));
        assertEquals("ads", WarehouseLayerResolver.resolve("ADS.DB", "whatever"));
    }

    @Test
    @DisplayName("库名推断不出时退回表名前缀")
    void fallsBackToTableNamePrefix() {
        // 所有表平铺在 default 库的情况，这是历史种子数据的形态。
        assertEquals("ods", WarehouseLayerResolver.resolve("default", "ods_orders"));
        assertEquals("dwd", WarehouseLayerResolver.resolve("default", "dwd_orders"));
        assertEquals("ads", WarehouseLayerResolver.resolve(null, "ads_order_stats"));
    }

    @Test
    @DisplayName("层级名后必须跟分隔符，避免 adsense 被误判为 ads 层")
    void requiresSeparatorAfterLayerName() {
        // 这是收紧匹配逻辑的原因：裸前缀匹配会把这些名字错误归类。
        assertEquals("other", WarehouseLayerResolver.resolve("adsense", "adsense_log"));
        assertEquals("other", WarehouseLayerResolver.resolve("odyssey", "odyssey_events"));
        assertEquals("other", WarehouseLayerResolver.resolve("dimension_helper", "dimension_helper"));
        assertEquals("other", WarehouseLayerResolver.resolve("public", "adsvertisement"));
    }

    @Test
    @DisplayName("大小写与首尾空白不影响推断")
    void normalizesCaseAndWhitespace() {
        assertEquals("ods", WarehouseLayerResolver.resolve("  ODS  ", "ORDER"));
        assertEquals("dwd", WarehouseLayerResolver.resolve("DWD_ecommerce", "x"));
    }

    @Test
    @DisplayName("库名与表名都匹配不上时归为 other，而不是抛异常或返回空")
    void fallsBackToOther() {
        assertEquals("other", WarehouseLayerResolver.resolve("public", "sys_config"));
        assertEquals("other", WarehouseLayerResolver.resolve(null, null));
        assertEquals("other", WarehouseLayerResolver.resolve("", ""));
        assertEquals("other", WarehouseLayerResolver.resolve("   ", "   "));
    }

    @Test
    @DisplayName("库名优先于表名：库名命中时不再看表名")
    void schemaTakesPrecedenceOverTable() {
        // 一张放在 ods 库里但命名像 ads 的表，应归入 ods —— 物理归属比命名更可信。
        assertEquals("ods", WarehouseLayerResolver.resolve("ods", "ads_snapshot"));
    }

    @Test
    @DisplayName("连字符与点号同样视为分隔符")
    void acceptsAllSeparators() {
        assertEquals("ods", WarehouseLayerResolver.resolve("ods-tmp", "x"));
        assertEquals("dws", WarehouseLayerResolver.resolve("dws.staging", "x"));
    }
}
