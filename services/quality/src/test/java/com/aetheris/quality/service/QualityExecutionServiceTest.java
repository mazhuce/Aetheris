package com.aetheris.quality.service;

import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.entity.QualityRule;
import com.aetheris.quality.entity.QualityQuarantineRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

class QualityExecutionServiceTest {

    private final QualityRuleService ruleService = mock(QualityRuleService.class);
    private final QualityCheckResultService resultService = mock(QualityCheckResultService.class);
    private final QualityQueryClient queryClient = mock(QualityQueryClient.class);
    private final NotificationClient notificationClient = mock(NotificationClient.class);
    private final QualityExecutionService service = new QualityExecutionService(
            ruleService, resultService, queryClient, notificationClient);

    @Test
    void buildsAndExecutesPredicateRule() {
        QualityRule rule = rule("not_null", "order_id IS NOT NULL");
        when(ruleService.getById(1L)).thenReturn(rule);
        when(queryClient.violationCount(7L,
                "SELECT COUNT(*) AS violation_count FROM ods.orders WHERE NOT (order_id IS NOT NULL)"))
                .thenReturn(0L);

        QualityCheckResult result = service.execute(1L, "admin");

        assertEquals("passed", result.getStatus());
        assertEquals(0L, result.getViolationCount());
        assertEquals("passed", rule.getLastCheckStatus());
    }

    @Test
    void failsWhenViolationsExceedThreshold() {
        QualityRule rule = rule("range", "amount >= 0");
        rule.setThreshold(2);
        when(ruleService.getById(1L)).thenReturn(rule);
        when(queryClient.violationCount(7L,
                "SELECT COUNT(*) AS violation_count FROM ods.orders WHERE NOT (amount >= 0)"))
                .thenReturn(3L);

        assertEquals("failed", service.execute(1L, "admin").getStatus());
    }

    @Test
    void recordsFailedCheckInQuarantine() {
        QualityQuarantineService quarantineService = mock(QualityQuarantineService.class);
        QualityExecutionService quarantiningService = new QualityExecutionService(
                ruleService, resultService, queryClient, notificationClient, quarantineService);
        QualityRule rule = rule("range", "amount >= 0");
        rule.setThreshold(2);
        when(ruleService.getById(1L)).thenReturn(rule);
        when(queryClient.violationCount(7L,
                "SELECT COUNT(*) AS violation_count FROM ods.orders WHERE NOT (amount >= 0)"))
                .thenReturn(3L);

        quarantiningService.execute(1L, "admin");

        org.mockito.ArgumentCaptor<QualityQuarantineRecord> captor =
                org.mockito.ArgumentCaptor.forClass(QualityQuarantineRecord.class);
        verify(quarantineService).save(captor.capture());
        assertEquals("open", captor.getValue().getStatus());
        assertEquals(3L, captor.getValue().getViolationCount());
    }

    @Test
    void buildsDuplicateRuleUsingExcessRows() {
        QualityRule rule = rule("duplicate", "order_id, line_no");

        assertEquals(
                "SELECT COALESCE(SUM(duplicate_count - 1), 0) AS violation_count FROM "
                        + "(SELECT order_id, line_no, COUNT(*) AS duplicate_count FROM ods.orders "
                        + "GROUP BY order_id, line_no HAVING COUNT(*) > 1) quality_duplicates",
                service.buildSql(rule));
    }

    @Test
    void recordsExecutionErrors() {
        QualityRule rule = rule("not_null", "order_id IS NOT NULL");
        when(ruleService.getById(1L)).thenReturn(rule);
        when(queryClient.violationCount(7L, service.buildSql(rule)))
                .thenThrow(new QualityQueryClient.QualityExecutionException("数据源不可用"));

        assertThrows(QualityQueryClient.QualityExecutionException.class,
                () -> service.execute(1L, "admin"));

        ArgumentCaptor<QualityCheckResult> result = ArgumentCaptor.forClass(QualityCheckResult.class);
        verify(resultService).updateById(result.capture());
        assertEquals("error", result.getValue().getStatus());
        assertEquals("QUERY_FAILED", result.getValue().getFailureCode());
        assertEquals("error", rule.getLastCheckStatus());
    }

    @Test
    void rejectsCommentOrMultipleStatements() {
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSql(rule("sql", "SELECT 0; DROP TABLE users")));
    }

    @Test
    void predicateUsesAstAndFunctionWhitelist() {
        assertEquals(
                "SELECT COUNT(*) AS violation_count FROM ods.orders WHERE NOT (COALESCE(amount, 0) >= 0 AND status IN ('paid', 'done'))",
                service.buildSql(rule("range", "COALESCE(amount, 0) >= 0 AND status IN ('paid', 'done')")));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSql(rule("range", "amount >= 0 OR pg_sleep(1) = 0")));
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSql(rule("range", "amount >= (SELECT 0)")));
    }

    @Test
    void customSqlRejectsFunctionsOutsideWhitelist() {
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSql(rule("sql", "SELECT dangerous_fn(order_id) FROM ods.orders")));
    }

    private QualityRule rule(String type, String expression) {
        QualityRule rule = new QualityRule();
        rule.setId(1L);
        rule.setDatasourceId(7L);
        rule.setRuleType(type);
        rule.setTargetTable("ods.orders");
        rule.setExpression(expression);
        rule.setThreshold(0);
        rule.setStatus("enabled");
        return rule;
    }
}
