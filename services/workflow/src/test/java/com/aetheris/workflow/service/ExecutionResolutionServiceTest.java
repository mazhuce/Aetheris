package com.aetheris.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionResolutionServiceTest {

    @Test
    void rejectsUnsupportedTargetBeforeOpeningTheDatabaseTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExecutionResolutionService service = service(jdbc);

        assertThrows(IllegalArgumentException.class,
                () -> service.resolve(7L, "timed_out", "checked", "ticket-1", "admin"));

        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesToOverrideAnUnknownAttemptThatStillHasAManagedHandle() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = unknownAttemptRow("spark", "managed-42");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(((RowMapper<Object>) invocation.getArgument(1)).mapRow(row, 0)));

        ExecutionResolutionService.ResolutionConflictException error = assertThrows(
                ExecutionResolutionService.ResolutionConflictException.class,
                () -> service(jdbc).resolve(7L, "failed", "driver failed", "ticket-1", "admin"));

        assertTrue(error.getMessage().contains("自动对账"));
        verify(jdbc, never()).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesTheExactHandleLessAttemptAndWritesAnAuditEvent() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet unknown = unknownAttemptRow("spark", null);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(((RowMapper<Object>) invocation.getArgument(1)).mapRow(unknown, 0)));

        ResultSet inserted = mock(ResultSet.class);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-10T08:00:00+08:00");
        when(inserted.getLong("id")).thenReturn(31L);
        when(inserted.getLong("instance_id")).thenReturn(7L);
        when(inserted.getLong("attempt_id")).thenReturn(9L);
        when(inserted.getString("from_status")).thenReturn("unknown");
        when(inserted.getString("resolved_status")).thenReturn("success");
        when(inserted.getString("reason")).thenReturn("history server confirms success");
        when(inserted.getString("evidence_ref")).thenReturn("application_123");
        when(inserted.getString("resolved_by")).thenReturn("admin");
        when(inserted.getObject("created_at", OffsetDateTime.class)).thenReturn(createdAt);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> ((RowMapper<Object>) invocation.getArgument(1)).mapRow(inserted, 0));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ExecutionResolutionService.ResolutionView result = service(jdbc).resolve(
                7L, "success", " history server confirms success ", " application_123 ", "admin");

        assertEquals(31L, result.id());
        assertEquals("success", result.resolvedStatus());
        assertEquals("application_123", result.evidenceRef());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(4)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().get(0).contains("execution_attempt"));
        assertTrue(sql.getAllValues().get(1).contains("task_instance"));
        assertTrue(sql.getAllValues().get(2).contains("manual_resolution"));
        assertTrue(sql.getAllValues().get(3).contains("spark_resource_allocation"));
    }

    private static ResultSet unknownAttemptRow(String taskType, String remoteHandle) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("instance_id")).thenReturn(7L);
        when(row.getLong("active_attempt_id")).thenReturn(9L);
        when(row.getString("instance_status")).thenReturn("unknown");
        when(row.getString("attempt_status")).thenReturn("unknown");
        when(row.getString("task_type")).thenReturn(taskType);
        when(row.getString("remote_handle")).thenReturn(remoteHandle);
        return row;
    }

    private static ExecutionResolutionService service(JdbcTemplate jdbc) {
        return new ExecutionResolutionService(jdbc, noOpTransactions(), new ObjectMapper());
    }

    private static PlatformTransactionManager noOpTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }
}
