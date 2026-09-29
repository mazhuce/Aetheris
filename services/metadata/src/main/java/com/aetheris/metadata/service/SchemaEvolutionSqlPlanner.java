package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseSchemaChange;
import com.aetheris.metadata.entity.LakehouseTable;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a single, bounded Iceberg ALTER TABLE statement from a reviewed change. */
public final class SchemaEvolutionSqlPlanner {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern DECIMAL = Pattern.compile("(?i)decimal\\s*\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)");
    private static final Pattern SIMPLE_TYPE = Pattern.compile(
            "(?i)(boolean|tinyint|smallint|int|integer|bigint|float|double|string|date|timestamp|binary|uuid)");

    private SchemaEvolutionSqlPlanner() { }

    public static Plan plan(LakehouseTable table, LakehouseSchemaChange change) {
        if (table == null || !StringUtils.hasText(table.getIdentifier())) {
            throw new IllegalArgumentException("湖仓表不存在或标识符为空");
        }
        if (change == null || !StringUtils.hasText(change.getOperation())) {
            throw new IllegalArgumentException("Schema 变更为空");
        }
        String operation = change.getOperation().trim().toLowerCase(Locale.ROOT);
        if ("narrow_type".equals(operation) || "isolated".equals(change.getStatus())) {
            throw new IllegalArgumentException("隔离的 Schema 变更不能生成执行计划");
        }
        if ("rejected".equals(change.getStatus()) || "applied".equals(change.getStatus())) {
            throw new IllegalArgumentException("已拒绝或已应用的 Schema 变更不能再次生成计划");
        }
        if (SetForApproval.requiresApproval(operation) && !"approved".equals(change.getStatus())) {
            throw new IllegalArgumentException("破坏性 Schema 变更必须先审批");
        }

        String tableName = quoteQualified(table.getIdentifier());
        String column = quotePart(change.getColumnName(), "columnName");
        boolean ready = "auto_apply".equals(change.getDecision()) || "approved".equals(change.getStatus());
        return switch (operation) {
            case "add_column" -> new Plan(operation,
                    "ALTER TABLE " + tableName + " ADD COLUMN " + column + " " + requireType(change.getToType()),
                    false, ready);
            case "widen_type" -> new Plan(operation,
                    "ALTER TABLE " + tableName + " ALTER COLUMN " + column + " TYPE " + requireType(change.getToType()),
                    false, ready);
            case "rename_column" -> new Plan(operation,
                    "ALTER TABLE " + tableName + " RENAME COLUMN " + column + " TO "
                            + quotePart(change.getNewColumnName(), "newColumnName"),
                    true, ready);
            case "drop_column" -> new Plan(operation,
                    "ALTER TABLE " + tableName + " DROP COLUMN " + column,
                    true, ready);
            case "comment" -> new Plan(operation,
                    "ALTER TABLE " + tableName + " ALTER COLUMN " + column + " COMMENT '"
                            + requireComment(change.getReason()) + "'",
                    false, ready);
            default -> throw new IllegalArgumentException("不支持的 Schema 变更类型：" + operation);
        };
    }

    private static String requireType(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("toType 不能为空");
        String type = value.trim().replaceAll("\\s+", " ");
        if (SIMPLE_TYPE.matcher(type).matches()) return type.toUpperCase(Locale.ROOT);
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1));
            int scale = Integer.parseInt(decimal.group(2));
            if (precision >= 1 && precision <= 38 && scale >= 0 && scale <= precision) return type;
        }
        throw new IllegalArgumentException("toType 不是允许的 Iceberg 类型");
    }

    private static String quoteQualified(String identifier) {
        String[] parts = identifier.split("\\.");
        if (parts.length < 2 || parts.length > 8) throw new IllegalArgumentException("湖仓表标识符层级非法");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) result.append('.');
            result.append(quotePart(part, "tableIdentifier"));
        }
        return result.toString();
    }

    private static String quotePart(String value, String field) {
        if (!StringUtils.hasText(value) || !IDENTIFIER.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + " 格式非法");
        }
        return "`" + value.trim() + "`";
    }

    private static String requireComment(String value) {
        if (!StringUtils.hasText(value) || value.length() > 512 || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("comment 必须为 1-512 个单行字符");
        }
        return value.replace("'", "''");
    }

    private static final class SetForApproval {
        private SetForApproval() { }
        private static boolean requiresApproval(String operation) {
            return "rename_column".equals(operation) || "drop_column".equals(operation);
        }
    }

    public record Plan(String operation, String statement, boolean destructive, boolean readyForExecution) { }
}
