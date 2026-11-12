package com.aetheris.mcp.tools;

import com.aetheris.mcp.client.LearningReadClient;
import com.aetheris.mcp.client.McpToolException;
import com.aetheris.mcp.client.PlatformReadClient;
import com.aetheris.mcp.config.McpProperties;
import com.aetheris.mcp.support.Json;
import com.aetheris.mcp.tasks.McpTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** M2 只读工具 + M3/M4 草稿、审批与长任务工具。高风险动作只创建审批单。 */
@Component
public class ToolRegistry {

    private final McpProperties properties;
    private final McpTaskRepository tasks;
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public ToolRegistry(PlatformReadClient platform, LearningReadClient learning, McpTaskRepository tasks,
                        McpProperties properties) {
        this.properties = properties;
        this.tasks = tasks;
        register(new ToolDefinition("search_tables", "检索数据表",
                "按关键词检索数据仓库中的表，可按 ODS/DWD/DWS/ADS/DIM 层级过滤。",
                schema(Map.of(
                        "keyword", Map.of("type", "string", "description", "表名或业务关键词"),
                        "layer", Map.of("type", "string", "enum", List.of("ods", "dwd", "dws", "ads", "dim"))),
                        List.of("keyword")), 0,
                (user, args) -> json(platform.searchTables(user, text(args, "keyword", ""), text(args, "layer", "")))));

        register(new ToolDefinition("describe_table", "查看表结构",
                "读取指定表的字段、类型、注释与分区信息。",
                schema(Map.of("tableId", Map.of("type", "integer", "description", "元数据表 ID")),
                        List.of("tableId")), 0,
                (user, args) -> json(platform.describeTable(user, number(args, "tableId")))));

        register(new ToolDefinition("query_readonly_sql", "执行只读 SQL",
                "在指定数据源上执行只读 SQL。服务端会再次执行 SQL 守卫，任何写操作都会被拒绝。",
                schema(Map.of(
                        "datasourceId", Map.of("type", "integer", "description", "数据源 ID"),
                        "sql", Map.of("type", "string", "description", "单条只读 SELECT 语句")),
                        List.of("datasourceId", "sql")), 0,
                (user, args) -> json(platform.query(user, number(args, "datasourceId"), text(args, "sql", "")))));

        register(new ToolDefinition("search_metrics", "检索指标",
                "按关键词检索已发布的语义指标。",
                schema(Map.of("keyword", Map.of("type", "string", "description", "指标名称或编码关键词")),
                        List.of("keyword")), 0,
                (user, args) -> json(platform.searchMetrics(user, text(args, "keyword", "")))));

        register(new ToolDefinition("query_metric", "查询指标",
                "按指标编码查询指标值，支持维度、时间粒度与日期区间。",
                schema(Map.of(
                        "metricCode", Map.of("type", "string", "description", "指标编码"),
                        "dimensions", Map.of("type", "array", "items", Map.of("type", "string")),
                        "timeGrain", Map.of("type", "string", "enum", List.of("day", "week", "month")),
                        "startDate", Map.of("type", "string", "description", "开始日期 yyyy-MM-dd"),
                        "endDate", Map.of("type", "string", "description", "结束日期 yyyy-MM-dd")),
                        List.of("metricCode")), 0,
                (user, args) -> json(platform.queryMetric(user, text(args, "metricCode", ""), strings(args, "dimensions"),
                        text(args, "timeGrain", null), text(args, "startDate", null), text(args, "endDate", null)))));

        register(new ToolDefinition("get_learning_path", "获取学习路径",
                "获取当前学员的学习路径、进度与技能完成情况。",
                schema(Map.of(), List.of()), 0,
                (user, args) -> json(learning.learningPath(user))));

        register(new ToolDefinition("get_current_lab", "获取当前实验",
                "读取指定实验任务的任务卡、步骤、验收标准与最近一次评分。",
                schema(Map.of("labCode", Map.of("type", "string", "description", "实验任务编码")),
                        List.of("labCode")), 0,
                (user, args) -> json(learning.lab(user, text(args, "labCode", "")))));

        register(new ToolDefinition("get_grading_feedback", "获取评分反馈",
                "读取指定实验最近若干次提交的状态、分数与通过情况。",
                schema(Map.of("labCode", Map.of("type", "string", "description", "实验任务编码")),
                        List.of("labCode")), 0,
                (user, args) -> json(learning.submissions(user, text(args, "labCode", "")))));

        register(new ToolDefinition("draft_lab_publish", "发起任务发布审批",
                "为指定实验任务创建发布审批单。该工具不会直接发布内容，必须由内容管理员在教师后台批准。",
                schema(Map.of(
                        "labCode", Map.of("type", "string", "description", "实验任务编码"),
                        "reason", Map.of("type", "string", "description", "发布原因")),
                        List.of("labCode")), 1,
                (user, args) -> {
                    String labCode = text(args, "labCode", "");
                    String reason = text(args, "reason", "MCP 请求发布实验任务");
                    JsonNode approval = learning.requestLabPublish(user, labCode, reason);
                    long approvalId = approval.path("approvalId").asLong();
                    long taskId = tasks.insert(user.uid(), user.username(), "LAB_PUBLISH_APPROVAL",
                            "pending_approval", approvalId, labCode,
                            Json.write(Map.of("labCode", labCode, "reason", reason, "approvalId", approvalId)));
                    ObjectNode result = Json.MAPPER.createObjectNode();
                    result.put("taskId", taskId);
                    result.put("approvalId", approvalId);
                    result.put("labCode", labCode);
                    result.put("status", "pending_approval");
                    result.put("message", "审批单已创建，管理员批准后才会发布；MCP 不会直接执行写操作。");
                    return ToolResult.ok(result, result.toString());
                }));

        register(new ToolDefinition("get_approval_status", "查询审批状态",
                "查询发布审批的当前状态；审批通过后对应长任务会标记为完成。",
                schema(Map.of("approvalId", Map.of("type", "integer", "description", "审批单 ID")),
                        List.of("approvalId")), 0,
                (user, args) -> {
                    long approvalId = number(args, "approvalId");
                    JsonNode approval = learning.approval(user, approvalId);
                    tasks.findByApproval(approvalId, user.uid()).ifPresent(task -> syncTask(task, approval));
                    return json(approval);
                }));

        register(new ToolDefinition("draft_quality_rule", "生成质量规则草稿",
                "根据表、字段与规则类型生成质量核查 SQL 草稿。只生成草稿并记录任务，不在任何数据源执行。",
                schema(Map.of(
                        "table", Map.of("type", "string", "description", "表名，如 orders_raw"),
                        "column", Map.of("type", "string", "description", "字段名"),
                        "ruleType", Map.of("type", "string", "enum", List.of("not_null", "duplicate", "enum", "range")),
                        "values", Map.of("type", "array", "items", Map.of("type", "string")),
                        "min", Map.of("type", "number"),
                        "max", Map.of("type", "number")),
                        List.of("table", "column", "ruleType")), 0,
                (user, args) -> {
                    String table = text(args, "table", "");
                    String column = text(args, "column", "");
                    String ruleType = text(args, "ruleType", "not_null");
                    String sql = qualityRuleSql(table, column, ruleType, args);
                    ObjectNode draft = Json.MAPPER.createObjectNode();
                    draft.put("table", table);
                    draft.put("column", column);
                    draft.put("ruleType", ruleType);
                    draft.put("sql", sql);
                    draft.put("note", "草稿未执行；发布前需人工确认字段类型与阈值。");
                    long taskId = tasks.insert(user.uid(), user.username(), "QUALITY_RULE_DRAFT", "completed",
                            null, null, Json.write(Map.of("table", table, "column", column, "ruleType", ruleType)));
                    tasks.update(taskId, "completed", Json.write(draft), null);
                    draft.put("taskId", taskId);
                    return ToolResult.ok(draft, draft.toString());
                }));
    }

    private static String qualityRuleSql(String table, String column, String ruleType, JsonNode args) {
        if (!table.matches("[A-Za-z_][A-Za-z0-9_.]{0,62}") || !column.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new McpToolException("INVALID_ARGUMENT", "表名或字段名不合法");
        }
        return switch (ruleType) {
            case "not_null" -> "SELECT COUNT(*) AS violation_cnt FROM " + table + " WHERE " + column + " IS NULL";
            case "duplicate" -> "SELECT COUNT(*) AS duplicate_groups FROM (SELECT " + column + " FROM " + table
                    + " GROUP BY " + column + " HAVING COUNT(*) > 1) d";
            case "enum" -> {
                List<String> values = strings(args, "values");
                if (values.isEmpty()) throw new McpToolException("INVALID_ARGUMENT", "enum 规则需要 values");
                String quoted = values.stream().map(value -> "'" + value.replace("'", "''") + "'")
                        .reduce((left, right) -> left + "," + right).orElse("");
                yield "SELECT COUNT(*) AS violation_cnt FROM " + table + " WHERE " + column
                        + " NOT IN (" + quoted + ")";
            }
            case "range" -> {
                JsonNode min = args.get("min");
                JsonNode max = args.get("max");
                if (min == null || max == null || !min.isNumber() || !max.isNumber()) {
                    throw new McpToolException("INVALID_ARGUMENT", "range 规则需要 min 与 max");
                }
                yield "SELECT COUNT(*) AS violation_cnt FROM " + table + " WHERE " + column + " < " + min.asText()
                        + " OR " + column + " > " + max.asText();
            }
            default -> throw new McpToolException("INVALID_ARGUMENT", "不支持的规则类型：" + ruleType);
        };
    }

    private void syncTask(com.aetheris.mcp.tasks.McpTask task, JsonNode approval) {
        String status = approval.path("status").asText("");
        String mapped = switch (status) {
            case "approved" -> "completed";
            case "rejected" -> "rejected";
            default -> null;
        };
        if (mapped != null && !mapped.equals(task.status())) {
            tasks.update(task.id(), mapped, approval.toString(), null);
        }
    }

    private void register(ToolDefinition definition) {
        tools.put(definition.name(), definition);
    }

    public List<ToolDefinition> list() {
        return List.copyOf(tools.values());
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    private ToolResult json(JsonNode node) {
        if (node == null) return ToolResult.error("EMPTY_RESULT", "工具未返回内容。");
        String text = node.toString();
        int max = Math.max(500, properties.getMaxResultChars());
        if (text.length() > max) {
            text = text.substring(0, max) + "…（结果已截断，共 " + node.toString().length() + " 字符）";
        }
        return ToolResult.ok(node, text);
    }

    private static String text(JsonNode args, String field, String fallback) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) return fallback;
        return value.asText();
    }

    private static long number(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new McpToolException("INVALID_ARGUMENT", "参数 " + field + " 必须是整数。");
        }
        return value.asLong();
    }

    private static List<String> strings(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(node -> result.add(node.asText()));
        return result;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
