package com.aetheris.workflow.service;

import java.util.Map;
import java.util.Optional;

/**
 * Contract for a managed execution backend. Implementations must make submit
 * idempotent by request key and must not report cancelled before the remote work
 * has actually stopped.
 */
public interface ExecutionAdapter {

    ArtifactInfo validate(String artifactKey, String expectedDigest);

    ManagedHandle submit(Submission submission);

    RemoteExecution status(String remoteHandle);

    LogPage logs(String remoteHandle, long offset, int limit);

    RemoteExecution cancel(String remoteHandle);

    Optional<ManagedHandle> reconcile(String requestKey);

    AdapterHealth health();

    record ArtifactInfo(String key, String sha256, long size) { }

    record Submission(
            String requestKey,
            String artifactKey,
            String artifactDigest,
            Map<String, Object> parameters,
            int cores,
            int driverMemoryMb,
            int timeoutSeconds) { }

    record ManagedHandle(
            String id,
            String requestKey,
            String status,
            String applicationId,
            String artifactDigest) { }

    record RemoteExecution(
            String id,
            String status,
            String applicationId,
            Long affectedRows,
            String errorCode,
            String errorMessage,
            Map<String, Object> result,
            String cancellationConfirmedAt) {

        public boolean terminal() {
            return switch (status) {
                case "succeeded", "failed", "cancelled", "timed_out", "unknown" -> true;
                default -> false;
            };
        }
    }

    record LogPage(String content, long nextOffset, boolean eof) { }

    record AdapterHealth(String status, String version, int activeJobs, int maxConcurrency) {
        public boolean up() { return "UP".equals(status); }
    }
}
