package com.aetheris.quality.service;

import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.entity.QualityRule;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class QualityExecutionService {

    private static final Set<String> PREDICATE_TYPES = Set.of("not_null", "range", "enum");

    private final QualityRuleService ruleService;
    private final QualityCheckResultService resultService;
    private final QualityQueryClient queryClient;
    private final NotificationClient notificationClient;

    public QualityExecutionService(
            QualityRuleService ruleService,
            QualityCheckResultService resultService,
            QualityQueryClient queryClient,
            NotificationClient notificationClient) {
        this.ruleService = ruleService;
        this.resultService = resultService;
        this.queryClient = queryClient;
        this.notificationClient = notificationClient;
    }

    public QualityCheckResult execute(Long ruleId, String username) {
        QualityRule rule = ruleService.getById(ruleId);
        if (rule == null) throw new IllegalArgumentException("质量规则不存在");
        if (!"enabled".equals(rule.getStatus())) throw new IllegalStateException("质量规则已停用");
        String sql = buildSql(rule);
        long threshold = rule.getThreshold() == null ? 0L : rule.getThreshold();

        QualityCheckResult result = new QualityCheckResult();
        result.setRuleId(ruleId);
        result.setStatus("running");
        result.setThresholdValue(threshold);
        result.setTriggeredBy(StringUtils.hasText(username) ? username : "unknown");
        result.setStartedAt(OffsetDateTime.now());
        try {
            resultService.save(result);
        } catch (DataIntegrityViolationException error) {
            throw new IllegalStateException("该规则已有运行中的检查");
        }

        long startedAt = System.currentTimeMillis();
        try {
            long violations = queryClient.violationCount(rule.getDatasourceId(), sql);
            boolean passed = violations <= threshold;
            result.setStatus(passed ? "passed" : "failed");
            result.setViolationCount(violations);
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            result.setMessage(passed ? "质量检查通过" : "违规数超过阈值");
            result.setFinishedAt(OffsetDateTime.now());
            resultService.updateById(result);
            updateRule(rule, result.getStatus(), violations, null);
            if (!passed) {
                notificationClient.notifyAdmin(ruleId, "warning",
                        "质量检查未通过：" + rule.getName(),
                        "目标表 " + rule.getTargetTable() + " 发现 " + violations + " 条违规（阈值 " + threshold + "）");
            }
            return result;
        } catch (RuntimeException error) {
            String message = safeMessage(error);
            result.setStatus("error");
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            result.setMessage(message);
            result.setFinishedAt(OffsetDateTime.now());
            resultService.updateById(result);
            updateRule(rule, "error", null, message);
            notificationClient.notifyAdmin(ruleId, "critical",
                    "质量检查执行失败：" + rule.getName(), message);
            throw new QualityQueryClient.QualityExecutionException(message);
        }
    }

    String buildSql(QualityRule rule) {
        if (rule.getDatasourceId() == null) throw new IllegalArgumentException("请选择规则数据源");
        String target = requireQualifiedIdentifier(rule.getTargetTable());
        String expression = rule.getExpression() == null ? "" : rule.getExpression().trim();
        String type = rule.getRuleType() == null ? "" : rule.getRuleType().toLowerCase(Locale.ROOT);
        if (expression.isEmpty()) throw new IllegalArgumentException("规则表达式不能为空");
        rejectUnsafeExpression(expression);

        if (PREDICATE_TYPES.contains(type)) {
            return "SELECT COUNT(*) AS violation_count FROM " + target + " WHERE NOT (" + expression + ")";
        }
        if ("duplicate".equals(type)) {
            String columns = java.util.Arrays.stream(expression.split(","))
                    .map(String::trim)
                    .map(this::requireQualifiedIdentifier)
                    .collect(java.util.stream.Collectors.joining(", "));
            return "SELECT COALESCE(SUM(duplicate_count - 1), 0) AS violation_count FROM "
                    + "(SELECT " + columns + ", COUNT(*) AS duplicate_count FROM " + target
                    + " GROUP BY " + columns + " HAVING COUNT(*) > 1) quality_duplicates";
        }
        if ("sql".equals(type)) {
            if (!expression.toUpperCase(Locale.ROOT).matches("^(SELECT|WITH)\\b[\\s\\S]*")) {
                throw new IllegalArgumentException("自定义 SQL 必须是只读查询");
            }
            return expression;
        }
        throw new IllegalArgumentException("不支持的质量规则类型");
    }

    private String requireQualifiedIdentifier(String value) {
        if (!StringUtils.hasText(value) || !value.matches("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*){0,2}")) {
            throw new IllegalArgumentException("表名或字段名包含不支持的字符");
        }
        return value;
    }

    private void rejectUnsafeExpression(String expression) {
        String upper = expression.toUpperCase(Locale.ROOT);
        if (expression.contains(";") || upper.contains("--") || upper.contains("/*") || upper.contains("*/")) {
            throw new IllegalArgumentException("规则表达式只允许单条语句且不能包含注释");
        }
    }

    private void updateRule(QualityRule rule, String status, Long violations, String error) {
        rule.setLastCheckStatus(status);
        rule.setLastViolationCount(violations);
        rule.setLastCheckedAt(OffsetDateTime.now());
        rule.setLastError(error);
        rule.setUpdatedAt(OffsetDateTime.now());
        ruleService.updateById(rule);
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return StringUtils.hasText(message) ? message.substring(0, Math.min(message.length(), 512)) : "质量检查执行失败";
    }
}
