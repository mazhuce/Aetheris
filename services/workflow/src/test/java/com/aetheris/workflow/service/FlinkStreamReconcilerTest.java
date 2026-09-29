package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FlinkStreamReconcilerTest {
    private static final String JOB_ID = "0288779de388defe9e649cfb66b691c8";

    @Test
    @SuppressWarnings("unchecked")
    void finishedGatewayInsertBindsJobInsteadOfMarkingStreamSuccessful() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = row(null);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation ->
                List.of(((RowMapper<Object>) invocation.getArgument(1)).mapRow(row, 0)));
        FlinkSqlExecutionClient gateway = mock(FlinkSqlExecutionClient.class);
        ExecutionClaimCoordinator coordinator = mock(ExecutionClaimCoordinator.class);
        when(gateway.operationStatus("http://flink:8083", "s-1", "o-1"))
                .thenReturn(new FlinkSqlExecutionClient.GatewayOperationStatus("FINISHED", true));
        when(gateway.submittedJobId("http://flink:8083", "s-1", "o-1")).thenReturn(JOB_ID);

        new FlinkStreamReconciler(jdbc, gateway, coordinator).reconcileActiveStreams();

        verify(coordinator).bindDiscoveredFlinkJob(7L, 9L, "s-1/o-1", JOB_ID);
        verify(coordinator, never()).reconcileFlink(anyLong(), anyLong(), anyString());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(query.capture(), any(RowMapper.class));
        assertTrue(query.getValue().contains("ti.active_attempt_id=ea.id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void liveGatewayOperationRenewsPersistedLease() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = row(null);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation ->
                List.of(((RowMapper<Object>) invocation.getArgument(1)).mapRow(row, 0)));
        FlinkSqlExecutionClient gateway = mock(FlinkSqlExecutionClient.class);
        ExecutionClaimCoordinator coordinator = mock(ExecutionClaimCoordinator.class);
        when(gateway.operationStatus("http://flink:8083", "s-1", "o-1"))
                .thenReturn(new FlinkSqlExecutionClient.GatewayOperationStatus("RUNNING", false));

        new FlinkStreamReconciler(jdbc, gateway, coordinator).reconcileActiveStreams();

        verify(coordinator).renewFlinkLease(7L, 9L, "s-1/o-1");
        verify(coordinator, never()).reconcileFlink(anyLong(), anyLong(), anyString());
    }

    private static ResultSet row(String jobId) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(9L);
        when(row.getLong("instance_id")).thenReturn(7L);
        when(row.getString("remote_handle")).thenReturn("s-1/o-1");
        when(row.getString("flink_endpoint")).thenReturn("http://flink:8083");
        when(row.getString("flink_job_id")).thenReturn(jobId);
        return row;
    }
}
