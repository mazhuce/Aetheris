package com.aetheris.metadata.fixedexecution;

import org.springframework.util.StringUtils;
import java.util.*;

/** Frozen schemaVersion 1 SQL generator. Five rule kinds; no connection or authorization side effect. */
public final class MetadataQualitySqlGeneratorV1 {
    private static final Set<String> PREDICATE_TYPES=Set.of("not_null","range","enum");
    private MetadataQualitySqlGeneratorV1(){}
    public static String generate(String targetTable,String ruleType,String rawExpression) {
        String target = requireQualifiedIdentifier(targetTable);
        String expression = rawExpression == null ? "" : rawExpression.trim();
        String type = ruleType == null ? "" : ruleType.toLowerCase(Locale.ROOT);
        if (expression.isEmpty()) throw new IllegalArgumentException("规则表达式不能为空");
        rejectUnsafeExpression(expression);

        if (PREDICATE_TYPES.contains(type)) {
            MetadataQualityExpressionAstV1.validatePredicate(expression);
            return "SELECT COUNT(*) AS violation_count FROM " + target + " WHERE NOT (" + expression + ")";
        }
        if ("duplicate".equals(type)) {
            String columns = java.util.Arrays.stream(expression.split(","))
                    .map(String::trim)
                    .map(MetadataQualitySqlGeneratorV1::requireQualifiedIdentifier)
                    .collect(java.util.stream.Collectors.joining(", "));
            return "SELECT COALESCE(SUM(duplicate_count - 1), 0) AS violation_count FROM "
                    + "(SELECT " + columns + ", COUNT(*) AS duplicate_count FROM " + target
                    + " GROUP BY " + columns + " HAVING COUNT(*) > 1) quality_duplicates";
        }
        if ("sql".equals(type)) {
            if (!expression.toUpperCase(Locale.ROOT).matches("^(SELECT|WITH)\\b[\\s\\S]*")) {
                throw new IllegalArgumentException("自定义 SQL 必须是只读查询");
            }
            MetadataQualityExpressionAstV1.validateSqlFunctions(expression);
            return expression;
        }
        throw new IllegalArgumentException("不支持的质量规则类型");
    }

    private static String requireQualifiedIdentifier(String value) {
        if (!StringUtils.hasText(value) || !value.matches("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*){0,2}")) {
            throw new IllegalArgumentException("表名或字段名包含不支持的字符");
        }
        return value;
    }

    private static void rejectUnsafeExpression(String expression) {
        String upper = expression.toUpperCase(Locale.ROOT);
        if (expression.contains(";") || upper.contains("--") || upper.contains("/*") || upper.contains("*/")) {
            throw new IllegalArgumentException("规则表达式只允许单条语句且不能包含注释");
        }
        if (java.util.regex.Pattern.compile("\\b(DROP|TRUNCATE|ALTER|INSERT|UPDATE|DELETE|GRANT|REVOKE|COPY|CALL|INTO|OUTFILE|DUMPFILE|DIRECTORY|LOCATION|PG_READ_FILE|LOAD_FILE|SLEEP)\\b")
                .matcher(upper).find()) {
            throw new IllegalArgumentException("规则表达式包含禁止的 SQL 关键字");
        }
        if (!expression.matches("[A-Za-z0-9_.$\\s(),.'\"<>!=+*/%-]+")) {
            throw new IllegalArgumentException("规则表达式包含不支持的字符");
        }
    }

}
