package com.aetheris.quality.service;
import com.aetheris.quality.entity.*;
import com.aetheris.quality.fixedexecution.*;
import com.aetheris.quality.security.V2QualityFixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class QualityExecutionServiceTest {
 final QualityExecutionStore store=mock(QualityExecutionStore.class);
 final QualityQueryClient client=mock(QualityQueryClient.class);
 final ExecutionRecoveryService recovery=mock(ExecutionRecoveryService.class);
 final NotificationClient notifications=mock(NotificationClient.class);
 final QualityExecutionService service=new QualityExecutionService(new QualityFixedExecutionFacade(store,client,recovery,notifications));
 QualityExecutionStore.Claim claim(boolean reused){var result=new QualityCheckResult();result.setId(11L);result.setRuleId(8L);result.setStatus("running");return new QualityExecutionStore.Claim(result,2,"a".repeat(64),2,"sealed.orders",reused);}
 @Test void successfulFixedResultUsesSealedThresholdAndFencedCompletion(){var c=claim(false);when(store.claimScheduled(8,9,"fire:1")).thenReturn(c);when(client.execute(eq(c.submission()),any())).thenReturn(new QualityQueryClient.QueryResult(2,45));when(recovery.finish(any(),isNull())).thenReturn(true);var result=service.executeScheduled(8L,9,"fire:1");assertEquals("passed",result.getStatus());assertEquals(2L,result.getViolationCount());assertEquals(45L,result.getDurationMs());verify(recovery).finish(result,null);}
 @Test void failedThresholdCreatesQuarantineInSameFencedCompletion(){var c=claim(false);when(store.claimScheduled(8,9,"fire:2")).thenReturn(c);when(client.execute(eq(c.submission()),any())).thenReturn(new QualityQueryClient.QueryResult(3,45));when(recovery.finish(any(),any())).thenReturn(true);assertEquals("failed",service.executeScheduled(8L,9,"fire:2").getStatus());var q=org.mockito.ArgumentCaptor.forClass(QualityQuarantineRecord.class);verify(recovery).finish(eq(c.result()),q.capture());assertEquals("sealed.orders",q.getValue().getTargetTable());assertEquals(11L,q.getValue().getResultId());assertEquals(3L,q.getValue().getViolationCount());}
 @Test void completedRequestKeyReusesResultWithoutQuery(){var c=claim(true);when(store.claimScheduled(8,9,"done")).thenReturn(c);assertSame(c.result(),service.executeScheduled(8L,9,"done"));verifyNoInteractions(client,recovery);}
 @Test void obsoleteGenerationCannotReachHttp(){when(store.claimScheduled(8,8,"old")).thenThrow(QualityExecutionStore.conflict());assertThrows(IllegalStateException.class,()->service.executeScheduled(8L,8,"old"));verifyNoInteractions(client,recovery);}
 @Test void localCompletionFailureRetainsUnknownInsteadOfReleasingSlot(){var c=claim(false);when(store.claimScheduled(8,9,"fail")).thenReturn(c);when(client.execute(eq(c.submission()),any())).thenReturn(new QualityQueryClient.QueryResult(3,45));when(recovery.finish(any(),any())).thenThrow(new IllegalStateException("database"));var e=assertThrows(QualityQueryClient.QualityExecutionException.class,()->service.executeScheduled(8L,9,"fail"));assertTrue(e.outcomeUnknown());assertEquals("unknown",c.result().getStatus());assertNull(c.result().getViolationCount());verify(recovery,times(2)).finish(any(),any());}
 @Test void signedFailureAndUnknownRemainDistinct(){for(boolean unknown:new boolean[]{false,true}){var c=claim(false);when(store.claimScheduled(8,9,"error")).thenReturn(c);when(client.execute(eq(c.submission()),any())).thenThrow(new QualityQueryClient.QualityExecutionException("safe",unknown));when(recovery.finish(any(),isNull())).thenReturn(true);assertEquals(unknown,assertThrows(QualityQueryClient.QualityExecutionException.class,()->service.executeScheduled(8L,9,"error")).outcomeUnknown());assertEquals(unknown?"unknown":"error",c.result().getStatus());}}
 @Test void notificationFailureCannotRepeatOrReclassifyExecution(){var c=claim(false);when(store.claimScheduled(8,9,"notice")).thenReturn(c);when(client.execute(eq(c.submission()),any())).thenReturn(new QualityQueryClient.QueryResult(3,45));when(recovery.finish(any(),any())).thenReturn(true);doThrow(new IllegalStateException()).when(notifications).notifyAdmin(any(),any(),any(),any());assertEquals("failed",service.executeScheduled(8L,9,"notice").getStatus());verify(client).execute(eq(c.submission()),any());}
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

    @Test
    void multilineRulesCannotHideWriteOrFileOperations() {
        for (String sql : java.util.List.of(
                "WITH x AS (\nDELETE FROM ods.orders RETURNING *) SELECT COUNT(*) FROM x",
                "SELECT COUNT(*)\nINTO OUTFILE '/tmp/no-file' FROM ods.orders",
                "SELECT COUNT(*) FROM ods.orders\nFOR UPDATE")) {
            assertThrows(IllegalArgumentException.class, () -> service.buildSql(rule("sql", sql)));
        }
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
