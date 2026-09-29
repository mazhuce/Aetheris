package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class FlinkJobLifecycleReconcilerTest {
    @Test
    void runningRemoteJobRecoversUnknownWithoutFinishingAttempt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<List<Object>> arguments = captureUpdates(jdbc);
        FlinkJobLifecycleReconciler reconciler = new FlinkJobLifecycleReconciler(jdbc, mock(FlinkJobManagerClient.class));

        reconciler.reconcile(new FlinkJobLifecycleReconciler.RemoteAttempt(
                11L, 22L, "job-11", "unknown", "resume"), "RUNNING", 17L);

        assertTrue(arguments.stream().flatMap(List::stream).anyMatch(value -> Long.valueOf(17L).equals(value)));
        assertTrue(arguments.stream().noneMatch(values -> values.contains("FLINK_JOBMANAGER_UNAVAILABLE")));
    }

    @Test
    void unexpectedFinishedStreamingJobIsFailureNotSuccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<List<Object>> arguments = captureUpdates(jdbc);
        FlinkJobLifecycleReconciler reconciler = new FlinkJobLifecycleReconciler(jdbc, mock(FlinkJobManagerClient.class));

        reconciler.reconcile(new FlinkJobLifecycleReconciler.RemoteAttempt(
                12L, 23L, "job-12", "running", null), "FINISHED", 19L);

        assertTrue(arguments.stream().flatMap(List::stream)
                .anyMatch(value -> "FLINK_UNEXPECTED_FINISH".equals(value)));
        assertTrue(arguments.stream().flatMap(List::stream)
                .noneMatch(value -> "success".equals(value)));
    }

    @Test
    void unknownRemoteStatePersistsUnknownEvidence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<List<Object>> arguments = captureUpdates(jdbc);
        FlinkJobLifecycleReconciler reconciler = new FlinkJobLifecycleReconciler(jdbc, mock(FlinkJobManagerClient.class));

        reconciler.reconcile(new FlinkJobLifecycleReconciler.RemoteAttempt(
                13L, 24L, "job-13", "running", "upgrade"), "MYSTERY", null);

        assertTrue(arguments.stream().flatMap(List::stream)
                .anyMatch(value -> String.valueOf(value).contains("MYSTERY")));
        assertTrue(arguments.size() >= 3);
    }

    @Test
    void suspendedOrTransitioningRemoteJobNeverBecomesTerminalLocally() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<List<Object>> arguments = captureUpdates(jdbc);
        FlinkJobLifecycleReconciler reconciler = new FlinkJobLifecycleReconciler(jdbc, mock(FlinkJobManagerClient.class));

        reconciler.reconcile(new FlinkJobLifecycleReconciler.RemoteAttempt(
                14L, 25L, "job-14", "running", null), "SUSPENDED", null);
        reconciler.reconcile(new FlinkJobLifecycleReconciler.RemoteAttempt(
                15L, 26L, "job-15", "running", null), "FAILING", null);

        assertTrue(arguments.stream().flatMap(List::stream)
                .noneMatch(value -> "failed".equals(value) || "success".equals(value)));
        assertTrue(arguments.stream().flatMap(List::stream)
                .anyMatch(value -> String.valueOf(value).contains("SUSPENDED")));
    }

    private static List<List<Object>> captureUpdates(JdbcTemplate jdbc) {
        List<List<Object>> arguments = new ArrayList<>();
        doAnswer(invocation -> {
            Object[] raw = invocation.getArguments();
            arguments.add(Arrays.asList(Arrays.copyOfRange(raw, 1, raw.length)));
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
        return arguments;
    }
}
