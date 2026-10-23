package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.function.LongConsumer;
import static org.junit.jupiter.api.Assertions.*;

/** Contract regressions added before the SQL-only deadline implementation. */
class SqlSourceDeadlineTest {
    @Test void sourceDeadlineIsAnExplicitClientInputRatherThanTheOneHourCompatibilityBudget() {
        assertDoesNotThrow(()->SqlExecutionClient.class.getMethod("execute",SqlExecutionClient.Submission.class,OffsetDateTime.class,LongConsumer.class));
    }
    @Test void knownSqlFailureHasItsOwnFencedCompletionRatherThanGenericFailure() {
        assertDoesNotThrow(()->ExecutionClaimCoordinator.class.getMethod("failFixedSql",ExecutionClaimCoordinator.ExecutionClaim.class,String.class));
    }
    @Test void sqlClaimNormalizesNullAndRejectsInvalidTimeoutBeforeDatabaseAccess() {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var manager=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var coordinator=new ExecutionClaimCoordinator(jdbc,manager,new com.fasterxml.jackson.databind.ObjectMapper(),"sql-test",600);
        var task=new com.aetheris.workflow.entity.Task();task.setTaskType("sql");
        for(int timeout:new int[]{-1,0,86401})assertThrows(IllegalArgumentException.class,()->coordinator.claimFixedSql(task,"2026-10-18","manual","alice",timeout));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
        assertThrows(IllegalStateException.class,()->coordinator.claimFixedSql(task,"2026-10-18","manual","alice",null));
        org.mockito.Mockito.verify(jdbc).queryForObject(org.mockito.ArgumentMatchers.contains("clock_timestamp()"),org.mockito.ArgumentMatchers.eq(OffsetDateTime.class),org.mockito.ArgumentMatchers.eq(1860L));
    }
}
