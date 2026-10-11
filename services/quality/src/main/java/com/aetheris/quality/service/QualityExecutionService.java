package com.aetheris.quality.service;

import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.entity.QualityRule;
import com.aetheris.quality.entity.QualityQuarantineRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class QualityExecutionService {

    private static final Logger log = LoggerFactory.getLogger(QualityExecutionService.class);

    private static final Set<String> PREDICATE_TYPES = Set.of("not_null", "range", "enum");

    private final QualityRuleService ruleService;
    private final QualityCheckResultService resultService;
    private final QualityQueryClient queryClient;
    private final NotificationClient notificationClient;
    private final QualityQuarantineService quarantineService;

    @Autowired
    public QualityExecutionService(
            QualityRuleService ruleService,
            QualityCheckResultService resultService,
            QualityQueryClient queryClient,
            NotificationClient notificationClient,
            QualityQuarantineService quarantineService) {
        this.ruleService = ruleService;
        this.resultService = resultService;
        this.queryClient = queryClient;
        this.notificationClient = notificationClient;
        this.quarantineService = quarantineService;
    }

    /** 保留单元测试及旧调用方构造方式；运行时由 Spring 使用带隔离服务的构造器。 */
    public QualityExecutionService(QualityRuleService ruleService,
                                   QualityCheckResultService resultService,
                                   QualityQueryClient queryClient,
                                   NotificationClient notificationClient) {
        this(ruleService, resultService, queryClient, notificationClient, null);
    }

    public QualityCheckResult execute(Long ruleId, String username) {
        QualityRule rule = ruleService.getById(ruleId);
        if (rule == null) throw new IllegalArgumentException("质量规则不存在");
        if (!"enabled".equals(rule.getStatus())) throw new IllegalStateException("质量规则已停用");
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
            String sql = buildSql(rule);
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
                createQuarantineRecord(rule, result, violations, threshold);
                notificationClient.notifyAdmin(ruleId, "warning",
                        "质量检查未通过：" + rule.getName(),
                        "目标表 " + rule.getTargetTable() + " 发现 " + violations + " 条违规（阈值 " + threshold + "）");
            }
            return result;
        } catch (RuntimeException error) {
            String message = safeMessage(error);
            result.setStatus("error");
            result.setFailureCode(failureCode(error));
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

    private void createQuarantineRecord(QualityRule rule, QualityCheckResult result,
                                        long violations, long threshold) {
        if (quarantineService == null) return;
        QualityQuarantineRecord record = new QualityQuarantineRecord();
        record.setResultId(result.getId());
        record.setRuleId(rule.getId());
        record.setTargetTable(rule.getTargetTable());
        record.setViolationCount(violations);
        record.setThresholdValue(threshold);
        record.setReason("违规数超过阈值");
        record.setStatus("open");
        try {
            quarantineService.save(record);
        } catch (RuntimeException error) {
            // 隔离记录是失败质量结果的必要组成部分。写入失败时 fail-closed，
            // 否则调用方会看到“已完成”但无法在隔离区找到对应诊断。
            log.error("Unable to persist quality quarantine record for rule {}", rule.getId(), error);
            throw new QuarantinePersistenceException("质量隔离记录写入失败，请重试", error);
        }
    }

    private String failureCode(RuntimeException error) {
        if (error instanceof IllegalArgumentException) return "INVALID_RULE";
        if (error instanceof QuarantinePersistenceException) return "QUARANTINE_WRITE_FAILED";
        if (error instanceof QualityQueryClient.QualityExecutionException) return "QUERY_FAILED";
        return "QUALITY_EXECUTION_FAILED";
    }

    String buildSql(QualityRule rule) {
        if (rule.getDatasourceId() == null) throw new IllegalArgumentException("请选择规则数据源");
        String target = requireQualifiedIdentifier(rule.getTargetTable());
        String expression = rule.getExpression() == null ? "" : rule.getExpression().trim();
        String type = rule.getRuleType() == null ? "" : rule.getRuleType().toLowerCase(Locale.ROOT);
        if (expression.isEmpty()) throw new IllegalArgumentException("规则表达式不能为空");
        rejectUnsafeExpression(expression);

        if (PREDICATE_TYPES.contains(type)) {
            QualityExpressionAst.validatePredicate(expression);
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
            QualityExpressionAst.validateSqlFunctions(expression);
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
        if (upper.matches(".*\\b(DROP|TRUNCATE|ALTER|INSERT|UPDATE|DELETE|GRANT|REVOKE|COPY|CALL|PG_READ_FILE|LOAD_FILE|SLEEP)\\b.*")) {
            throw new IllegalArgumentException("规则表达式包含禁止的 SQL 关键字");
        }
        if (!expression.matches("[A-Za-z0-9_.$\\s(),.'\"<>!=+*/%-]+")) {
            throw new IllegalArgumentException("规则表达式包含不支持的字符");
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

    public static class QuarantinePersistenceException extends RuntimeException {
        public QuarantinePersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
