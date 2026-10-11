package com.aetheris.aiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 暴露给大模型的数据工具集。
 *
 * <p>方法上的 {@code @Tool} 描述会直接进入模型的上下文，是它判断「该不该调用、怎么传参」的
 * 唯一依据，因此描述里写清用途、参数含义与调用顺序，而不只是复述方法名。
 *
 * <p>返回值刻意序列化成紧凑 JSON 字符串而非返回对象：Spring AI 会把返回值转成文本喂给模型，
 * 自己控制格式能避免默认序列化带上冗余字段，省下的 token 留给真正有用的表注释。
 *
 * <p>工具都不抛异常：失败时返回带 error 字段的 JSON，让模型看到原因后自行改写重试
 * （例如 SQL 语法错误、表名拼错）。抛异常会中断整轮对话，反而拿不到结果。
 */
@Component
public class DataTools {

    private static final Logger log = LoggerFactory.getLogger(DataTools.class);

    private final AgentToolClient client;
    private final ObjectMapper objectMapper;

    public DataTools(AgentToolClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Tool(description = """
            按关键词检索数据仓库中的表。这是回答任何数据问题的第一步，
            用于找到相关表及其 id。支持中文关键词匹配表名、表注释与业务域，
            例如「订单」「用户」「品类」。
            可选按数仓层级过滤：ods=贴源原始数据(含脏数据)，dwd=清洗后明细，
            dws=按主题汇总，ads=最终指标结果表，dim=维度表。
            回答统计类问题时优先用 ads 或 dws 层，它们的口径已经固化；
            ods 层保留原始脏数据，直接统计会得到错误结果。
            """)
    public String searchTables(
            @ToolParam(description = "检索关键词，如「订单」「品类销售」；留空则返回全部表")
            String keyword,
            @ToolParam(description = "数仓层级过滤，可选值 ods/dwd/dws/ads/dim；不确定时留空", required = false)
            String layer) {
        List<Map<String, Object>> tables = client.searchTables(keyword, layer);
        if (tables.isEmpty()) {
            return toJson(Map.of(
                    "tables", List.of(),
                    "hint", "未找到匹配的表，建议换个关键词或去掉层级过滤重试"));
        }
        return toJson(Map.of("tables", tables));
    }

    @Tool(description = """
            检索企业已经评审发布的业务指标。这是回答经营指标、转化率、金额、人数等标准口径问题的第一步。
            如果找到匹配指标，应优先调用 queryMetric，不要自行拼 SQL 重复计算；只有没有匹配指标时，
            才回退到 searchTables → describeTable → query 的探索流程。
            """)
    public String searchMetrics(
            @ToolParam(description = "指标业务关键词，如「成交金额」「订单转化率」") String keyword) {
        List<Map<String, Object>> metrics = client.searchMetrics(keyword);
        return toJson(Map.of("metrics", metrics,
                "hint", metrics.isEmpty() ? "未找到已发布指标，可回退到表检索流程" : "优先使用 queryMetric 查询统一口径"));
    }

    @Tool(description = """
            查询一个已经发布的企业业务指标。底层 SQL 由语义层统一生成，结果与看板、数据集完全同口径。
            dimensions 填 searchMetrics 返回的可用维度编码；timeGrain 支持 none/day/month/year；
            日期格式为 yyyy-MM-dd。不要用本工具查询未发布指标。
            """)
    public String queryMetric(
            @ToolParam(description = "已发布指标编码") String metricCode,
            @ToolParam(description = "维度编码列表；不分组时传空列表", required = false) List<String> dimensions,
            @ToolParam(description = "时间粒度 none/day/month/year", required = false) String timeGrain,
            @ToolParam(description = "开始日期 yyyy-MM-dd", required = false) String startDate,
            @ToolParam(description = "结束日期 yyyy-MM-dd", required = false) String endDate) {
        log.info("Agent semantic metric query: metricCode={}", metricCode);
        return toJson(client.queryMetric(metricCode, dimensions, timeGrain, startDate, endDate));
    }

    @Tool(description = """
            读取指定表的完整结构：字段名、类型与字段注释。
            必须先用 searchTables 拿到表 id 才能调用。
            写 SQL 前务必调用本工具确认字段真实存在——字段注释说明了业务含义，
            例如哪个字段是 GMV、哪个是有效订单标记，不要凭字段名猜测。
            """)
    public String describeTable(
            @ToolParam(description = "表 id，来自 searchTables 返回结果中的 id 字段")
            Long tableId) {
        return toJson(client.describeTable(tableId));
    }

    @Tool(description = """
            在指定数据源上执行只读 SQL 查询并返回结果。
            仅支持 SELECT / SHOW / DESCRIBE / EXPLAIN，任何写入语句都会被拒绝。
            调用前必须已用 describeTable 确认字段存在。
            重要：事实表按 dt 字段分区，查询时务必带上 dt 条件（如 WHERE dt = '2026-08-28'
            或 dt >= '2026-08-01'），否则会全表扫描导致超时。
            结果最多返回 100 行，聚合查询请在 SQL 里完成聚合而不是取回明细自行计算。
            """)
    public String query(
            @ToolParam(description = "数据源 id，来自 searchTables 或 describeTable 返回的 datasourceId")
            Long datasourceId,
            @ToolParam(description = "要执行的只读 SQL 语句")
            String sql) {
        String validationError = readOnlyValidationError(sql);
        if (validationError != null) {
            // metadata 仍是最终安全边界；这里提前拒绝可以避免把明显的写操作
            // 发送到内部服务，也让模型收到稳定、可改写的错误结构。
            return toJson(Map.of("error", validationError));
        }
        log.info("Agent query: datasourceId={}, sql={}", datasourceId, abbreviate(sql));
        return toJson(client.query(datasourceId, sql));
    }

    /**
     * Agent 的查询工具只允许单条只读语句。metadata 侧会再次执行完整的 SQL
     * 校验，这个轻量检查用于在网络调用前拦截写操作和多语句注入。
     */
    static String readOnlyValidationError(String sql) {
        if (sql == null || sql.isBlank()) {
            return "SQL 不能为空";
        }
        String statement = sql.trim()
                .replaceFirst("(?s)^/\\*.*?\\*/\\s*", "")
                .replaceFirst("(?m)^\\s*--[^\\r\\n]*[\\r\\n]", "")
                .trim();
        if (statement.isEmpty()) {
            return "SQL 不能为空";
        }
        // 只允许结尾分号；中间出现分号代表多语句。
        int semicolon = statement.indexOf(';');
        if (semicolon >= 0 && !statement.substring(semicolon + 1).trim().isEmpty()) {
            return "只允许执行单条只读 SQL";
        }
        String first = statement.substring(0, Math.min(statement.length(), 16))
                .split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("select", "show", "describe", "desc", "explain", "with").contains(first)) {
            return "仅支持 SELECT、SHOW、DESCRIBE、EXPLAIN 查询";
        }
        return null;
    }

    /** 序列化失败不应中断对话，退化成纯文本仍能让模型继续。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException error) {
            log.warn("Tool result serialization failed", error);
            return String.valueOf(value);
        }
    }

    private String abbreviate(String sql) {
        if (sql == null) {
            return "";
        }
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
