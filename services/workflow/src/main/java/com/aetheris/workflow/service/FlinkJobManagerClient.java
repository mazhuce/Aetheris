package com.aetheris.workflow.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Flink JobManager REST adapter for lifecycle operations that SQL Gateway does
 * not expose: checkpoints, savepoints, stop-with-savepoint and cancellation.
 * It never guesses a request outcome; callers receive the remote request id
 * and must poll the corresponding operation before persisting a terminal state.
 */
@Component
public class FlinkJobManagerClient {
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern TARGET = Pattern.compile("(?:s3|file|hdfs)://[^\\s]{1,1024}");

    private final RestClient client;

    @Autowired
    public FlinkJobManagerClient(
            RestClient.Builder builder,
            @Value("${aetheris.flink-jobmanager.base-url:http://127.0.0.1:18081}") String baseUrl,
            @Value("${aetheris.flink-jobmanager.connect-timeout:PT3S}") java.time.Duration connectTimeout,
            @Value("${aetheris.flink-jobmanager.read-timeout:PT10S}") java.time.Duration readTimeout) {
        // HttpURLConnection-backed SimpleClientHttpRequestFactory cannot send
        // PATCH, which Flink requires for cancelling a submitted Job.
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        this.client = builder.requestFactory(factory).baseUrl(trimBaseUrl(baseUrl)).build();
    }

    /** Package-private constructor keeps MockRestServiceServer able to inject its request factory. */
    FlinkJobManagerClient(RestClient client) {
        this.client = client;
    }

    public JobStatus status(String jobId) {
        String id = requireJobId(jobId);
        try {
            Map<?, ?> body = client.get().uri("/jobs/{jobId}", id).retrieve().body(Map.class);
            return new JobStatus(id, text(body, "state"), text(body, "name"));
        } catch (RestClientResponseException error) {
            throw remoteError("查询 Flink Job 状态", error);
        } catch (RuntimeException error) {
            throw new FlinkJobManagerException("无法连接 Flink JobManager", error);
        }
    }

    @SuppressWarnings("unchecked")
    public CheckpointSummary checkpoints(String jobId) {
        String id = requireJobId(jobId);
        try {
            Map<?, ?> body = client.get().uri("/jobs/{jobId}/checkpoints", id).retrieve().body(Map.class);
            Map<?, ?> latest = body == null ? Map.of() : map(body.get("latest"));
            Map<?, ?> completed = map(latest.get("completed"));
            Long checkpointId = number(completed.get("id"));
            return new CheckpointSummary(id, checkpointId, text(completed, "status"),
                    number(completed.get("trigger_timestamp")), number(completed.get("latest_ack_timestamp")));
        } catch (RestClientResponseException error) {
            throw remoteError("查询 Flink checkpoint", error);
        } catch (RuntimeException error) {
            if (error instanceof FlinkJobManagerException) throw error;
            throw new FlinkJobManagerException("无法查询 Flink checkpoint", error);
        }
    }

    public TriggeredOperation triggerSavepoint(String jobId, String targetDirectory, boolean cancelJob) {
        return trigger(jobId, "/jobs/{jobId}/savepoints",
                Map.of("targetDirectory", requireTarget(targetDirectory), "cancelJob", cancelJob));
    }

    public TriggeredOperation stopWithSavepoint(String jobId, String targetDirectory, boolean drain) {
        return trigger(jobId, "/jobs/{jobId}/stop",
                Map.of("targetDirectory", requireTarget(targetDirectory), "drain", drain,
                        "formatType", "CANONICAL"));
    }

    public SavepointStatus savepointStatus(String jobId, String requestId) {
        String id = requireJobId(jobId);
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("savepoint request id 格式非法");
        }
        try {
            Map<?, ?> body = client.get().uri("/jobs/{jobId}/savepoints/{requestId}", id, requestId)
                    .retrieve().body(Map.class);
            Map<?, ?> status = body == null ? Map.of() : map(body.get("status"));
            Map<?, ?> operation = body == null ? Map.of() : map(body.get("operation"));
            return new SavepointStatus(id, requestId, text(status, "id", "status"),
                    text(operation, "location"), text(operation, "failure-cause"));
        } catch (RestClientResponseException error) {
            throw remoteError("查询 Flink savepoint", error);
        } catch (RuntimeException error) {
            if (error instanceof FlinkJobManagerException) throw error;
            throw new FlinkJobManagerException("无法查询 Flink savepoint", error);
        }
    }

    public void cancel(String jobId) {
        String id = requireJobId(jobId);
        try {
            client.patch().uri("/jobs/{jobId}", id).body(Map.of("state", "CANCELED"))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException error) {
            throw remoteError("取消 Flink Job", error);
        } catch (RuntimeException error) {
            throw new FlinkJobManagerException("无法取消 Flink Job", error);
        }
    }

    private TriggeredOperation trigger(String jobId, String path, Map<String, Object> body) {
        String id = requireJobId(jobId);
        try {
            Map<?, ?> response = client.post().uri(path, id).body(body).retrieve().body(Map.class);
            String requestId = text(response, "request-id", "requestId");
            if (requestId == null) throw new FlinkJobManagerException("Flink JobManager 未返回操作 request id");
            return new TriggeredOperation(id, requestId);
        } catch (RestClientResponseException error) {
            throw remoteError("触发 Flink savepoint", error);
        } catch (FlinkJobManagerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new FlinkJobManagerException("无法触发 Flink savepoint", error);
        }
    }

    private static String requireJobId(String value) {
        if (value == null || !JOB_ID.matcher(value).matches()) throw new IllegalArgumentException("Flink Job ID 格式非法");
        return value;
    }

    private static String requireTarget(String value) {
        if (value == null || !TARGET.matcher(value).matches()) {
            throw new IllegalArgumentException("savepoint 目标目录必须是 s3://、hdfs:// 或 file:// URL");
        }
        return value;
    }

    private static String trimBaseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Flink JobManager 地址不能为空");
        return value.replaceAll("/+$", "");
    }

    private static String text(Map<?, ?> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> result ? result : Map.of();
    }

    private static FlinkJobManagerException remoteError(String action, RestClientResponseException error) {
        return new FlinkJobManagerException(action + "失败（HTTP " + error.getStatusCode().value() + "）", error);
    }

    public record JobStatus(String jobId, String state, String name) { }

    public record CheckpointSummary(String jobId, Long latestCompletedId, String status,
                                    Long triggerTimestamp, Long latestAckTimestamp) { }

    public record TriggeredOperation(String jobId, String requestId) { }

    public record SavepointStatus(String jobId, String requestId, String status,
                                  String location, String failureCause) { }

    public static class FlinkJobManagerException extends RuntimeException {
        public FlinkJobManagerException(String message) { super(message); }
        public FlinkJobManagerException(String message, Throwable cause) { super(message, cause); }
    }
}
