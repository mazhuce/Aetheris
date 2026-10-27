package com.aetheris.workflow.fixedexecution;

import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.ExecutionAdapter.RemoteExecution;
import com.aetheris.workflow.service.ExecutionClaimCoordinator;
import com.aetheris.workflow.service.FlinkJobManagerClient;
import com.aetheris.workflow.service.FlinkSqlExecutionClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real executor classification; source/receipt persistence has separate PostgreSQL tests. */
class WorkflowFlinkTerminalStateTest {
    @Test void boundedFinishedCompletesOnlyTheOriginalJobSuccessfully() {
        var h = new Harness("bounded");
        assertEquals("success", h.reconcile("FINISHED").getStatus());
        assertEquals("succeeded", h.completed.get().status());
        assertEquals(Harness.JOB, h.completed.get().result().get("jobId"));
        verify(h.manager).statusAt(Harness.MANAGER, Harness.JOB);
        verifyNoInteractions(h.gateway, h.coordinator);
    }

    @Test void legacyBatchFinishedRetainsItsReadbackCompletionMeaning() {
        var h = new Harness("batch");
        assertEquals("success", h.reconcile("FINISHED").getStatus());
        assertEquals("succeeded", h.completed.get().status());
        assertEquals("batch", h.source.material().path("mode").asText());
        verify(h.authority, never()).begin(any(), any(), anyString());
        verifyNoInteractions(h.gateway, h.coordinator);
    }

    @Test void streamingFinishedIsConfirmedFailureAndNeverPipelineSuccessEvidence() {
        var h = new Harness("streaming");
        assertEquals("failed", h.reconcile("FINISHED").getStatus());
        var result = h.completed.get();
        assertEquals("failed", result.status());
        assertEquals("FLINK_UNEXPECTED_FINISH", result.errorCode());
        assertNotNull(result.errorMessage());
        assertEquals("FINISHED", result.result().get("jobState"));
        assertEquals(h.source.handle(), result.id());
        assertNull(result.cancellationConfirmedAt());
        verify(h.authority, never()).complete(any(), argThat(remote -> "succeeded".equals(remote.status())));
        verifyNoInteractions(h.gateway, h.coordinator);
    }

    @Test void aCancellationRequestIsNotApprovedStopEvidenceForStreamingFinished() {
        var h = new Harness("streaming");
        h.instance.setStatus("cancel_requested");
        when(h.jdbc.queryForObject(eq(Harness.STATUS_SQL), eq(String.class), eq(22L))).thenReturn("cancel_requested");
        assertEquals("failed", h.reconcile("FINISHED").getStatus());
        assertEquals("FLINK_UNEXPECTED_FINISH", h.completed.get().errorCode());
        assertNull(h.completed.get().cancellationConfirmedAt());
        verifyNoInteractions(h.coordinator);
    }

    @Test void explicitCanceledJobRetainsConfirmedCancellationInsteadOfSuccess() {
        var h = new Harness("streaming");
        assertEquals("cancelled", h.reconcile("CANCELED").getStatus());
        assertEquals("cancelled", h.completed.get().status());
        assertNotNull(h.completed.get().cancellationConfirmedAt());
        assertDoesNotThrow(() -> OffsetDateTime.parse(h.completed.get().cancellationConfirmedAt()));
    }

    @Test void failedJobRemainsFailureForBothModes() {
        for (String mode : List.of("bounded", "streaming")) {
            var h = new Harness(mode);
            assertEquals("failed", h.reconcile("FAILED").getStatus());
            assertEquals("failed", h.completed.get().status());
            verifyNoInteractions(h.gateway, h.coordinator);
        }
    }

    @Test void runningOrIndeterminateRemoteStatesCannotCreateTerminalSuccess() {
        var h = new Harness("streaming");
        for (String state : List.of("RUNNING", "RESTARTING", "SUSPENDED", "MYSTERY")) {
            assertSame(h.instance, h.reconcile(state));
            assertEquals("running", h.instance.getStatus());
        }
        assertNull(h.completed.get());
        verify(h.authority, never()).complete(any(), any());
        verifyNoInteractions(h.gateway, h.coordinator);
    }

    @Test void unavailableJobManagerDoesNotInferAResultOrReplaySql() {
        var h = new Harness("streaming");
        when(h.manager.statusAt(Harness.MANAGER, Harness.JOB))
                .thenThrow(new FlinkJobManagerClient.FlinkJobManagerException("controlled unavailable"));
        assertThrows(FlinkJobManagerClient.FlinkJobManagerException.class, () -> h.executor.reconcile(h.claim));
        assertNull(h.completed.get());
        verify(h.authority, never()).complete(any(), any());
        verifyNoInteractions(h.gateway, h.coordinator);
    }

    @Test void undiscoveredJobKeepsTheOriginalReceiptWithoutSubmittingAgain() {
        var h = new Harness("streaming");
        when(h.jdbc.queryForObject(eq(Harness.JOB_SQL), eq(String.class), eq(22L))).thenReturn(null);
        when(h.gateway.submittedJobIdSealed(Harness.GATEWAY, "1".repeat(32), "2".repeat(32))).thenReturn(null);
        assertSame(h.instance, h.executor.reconcile(h.claim));
        assertNull(h.completed.get());
        verify(h.authority, never()).complete(any(), any());
        verify(h.gateway, never()).startSealed(anyString(), anyString(), anyBoolean(), anyLong(), any());
        verifyNoInteractions(h.manager, h.coordinator);
    }

    private static final class Harness {
        static final String JOB = "3".repeat(32);
        static final String GATEWAY = "http://127.0.0.1:19020", MANAGER = "http://127.0.0.1:19021";
        static final String JOB_SQL = "SELECT flink_job_id FROM workflow.execution_attempt WHERE id=?";
        static final String STATUS_SQL = "SELECT status FROM workflow.execution_attempt WHERE id=?";
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final WorkflowEngineAuthority authority = mock(WorkflowEngineAuthority.class);
        final ExecutionClaimCoordinator coordinator = mock(ExecutionClaimCoordinator.class);
        final FlinkSqlExecutionClient gateway = mock(FlinkSqlExecutionClient.class);
        final FlinkJobManagerClient manager = mock(FlinkJobManagerClient.class);
        final TaskInstance instance = new TaskInstance();
        final WorkflowExecutionStore.Claim claim;
        final WorkflowEngineAuthority.Source source;
        final AtomicReference<RemoteExecution> completed = new AtomicReference<>();
        final WorkflowFlinkExecutor executor;

        Harness(String mode) {
            instance.setId(11L); instance.setTaskId(8L); instance.setStatus("running");
            var execution = new ExecutionClaimCoordinator.ExecutionClaim(instance, 22L, 1, 3L, OffsetDateTime.now().plusMinutes(5));
            claim = new WorkflowExecutionStore.Claim(execution, 2L, "a".repeat(64), true);
            var material = FixedJson.JSON.createObjectNode().put("schemaVersion", 1).put("engine", "flink")
                    .put("gatewayOrigin", GATEWAY).put("jobManagerOrigin", MANAGER).put("mode", mode).put("queueTimeoutSeconds", 0);
            var manifest = FixedJson.JSON.createObjectNode();
            manifest.putObject("execution").set("engineDefinition", material.deepCopy());
            source = new WorkflowEngineAuthority.Source(claim, manifest, material,
                    WorkflowEngineMaterial.digest(material.toString()), UUID.randomUUID(), GATEWAY, null,
                    "1".repeat(32) + "/" + "2".repeat(32), "b".repeat(64));
            when(authority.original(claim)).thenReturn(source);
            when(jdbc.queryForObject(eq(JOB_SQL), eq(String.class), eq(22L))).thenReturn(JOB);
            when(jdbc.queryForObject(eq(STATUS_SQL), eq(String.class), eq(22L))).thenReturn("running");
            when(authority.complete(eq(claim), any(RemoteExecution.class))).thenAnswer(call -> {
                RemoteExecution result = call.getArgument(1);
                completed.set(result);
                instance.setStatus("succeeded".equals(result.status()) ? "success" : result.status());
                return instance;
            });
            executor = new WorkflowFlinkExecutor(jdbc, authority, coordinator, gateway, manager);
        }

        TaskInstance reconcile(String state) {
            when(manager.statusAt(MANAGER, JOB)).thenReturn(new FlinkJobManagerClient.JobStatus(JOB, state, "fixture"));
            return executor.reconcile(claim);
        }
    }
}
