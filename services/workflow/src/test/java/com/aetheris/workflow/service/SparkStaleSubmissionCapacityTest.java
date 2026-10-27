package com.aetheris.workflow.service;

import com.aetheris.workflow.service.ExecutionAdapter.ManagedHandle;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobView;
import com.aetheris.workflow.service.SparkResourceQueueService.Admission;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** A lost active fence after a physical POST is not evidence that the remote job stopped. */
class SparkStaleSubmissionCapacityTest {
    @Test void queuedDispatchMustKeepCapacityWhenItsPostSucceededButTheLocalFenceChanged() throws Exception {
        var rows = mock(ResultSet.class);
        when(rows.getLong("instance_id")).thenReturn(4L); when(rows.getLong("task_id")).thenReturn(3L);
        when(rows.getLong("instance_fencing_token")).thenReturn(7L); when(rows.getLong("attempt_id")).thenReturn(5L);
        when(rows.getInt("attempt")).thenReturn(1); when(rows.getObject("active_attempt_id")).thenReturn(5L);
        when(rows.getObject("job_definition_id")).thenReturn(9L);
        when(rows.getString("biz_date")).thenReturn("2024-03-02");
        when(rows.getString("instance_status")).thenReturn("submitting");
        when(rows.getString("attempt_status")).thenReturn("submitting");
        when(rows.getString("runner_base_url")).thenReturn("http://127.0.0.1:18081");
        var jdbc = new JdbcTemplate() {
            int calls;
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                if (++calls > 1) return List.of();
                try { return List.of(mapper.mapRow(rows, 0)); }
                catch (SQLException error) { throw new IllegalStateException(error); }
            }
        };
        var catalog = mock(ManagedSparkCatalogService.class); var adapter = mock(SparkBatchExecutionAdapter.class);
        var coordinator = mock(ExecutionClaimCoordinator.class); var queue = mock(SparkResourceQueueService.class);
        var job = mock(SparkJobView.class); when(catalog.job(9L)).thenReturn(job);
        when(queue.admitQueued(32)).thenReturn(List.of(new Admission(5L, 1L, "runner", "http://127.0.0.1:18081")));
        when(adapter.submit(eq("http://127.0.0.1:18081"), any())).thenReturn(
                new ManagedHandle("1".repeat(32), "attempt-5-f7", "running", null, "a".repeat(64)));
        when(coordinator.submitted(any(), any())).thenThrow(new ExecutionClaimCoordinator.StaleExecutionException("new fence"));
        var service = new SparkBatchExecutionService(catalog, adapter, coordinator, mock(TaskLogService.class),
                jdbc, mock(LineageEvidenceClient.class), queue);
        service.dispatchQueuedAttempts();
        verify(adapter, times(1)).submit(eq("http://127.0.0.1:18081"), any());
        verify(queue, never()).release(anyLong(), anyString());
        verify(coordinator, never()).fail(any(), anyString(), anyLong(), anyLong());
    }
}
