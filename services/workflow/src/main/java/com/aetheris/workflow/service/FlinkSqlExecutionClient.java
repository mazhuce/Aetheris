package com.aetheris.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal Flink SQL Gateway adapter. It deliberately targets the SQL Gateway
 * REST contract (session -> statement -> operation polling), not the lower-level
 * JobManager API, so SQL tasks can run in a mixed workflow DAG without shipping
 * a Flink client into the workflow service.
 */
@Component
public class FlinkSqlExecutionClient {
    private static final Logger log = LoggerFactory.getLogger(FlinkSqlExecutionClient.class);
    private static final long SAVEPOINT_SETUP_TIMEOUT_MS = 60_000L;
    private static final Pattern JOB_ID = Pattern.compile("[0-9a-fA-F]{32}");

    private final RestClient.Builder builder;
    private final Duration connectTimeout;
    private final long pollIntervalMs;
    private final ConcurrentMap<String, ActiveOperation> activeOperations = new ConcurrentHashMap<>();

    @Autowired
    public FlinkSqlExecutionClient(
            RestClient.Builder builder,
            @Value("${aetheris.flink-sql-gateway.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${aetheris.flink-sql-gateway.poll-interval-ms:1000}") long pollIntervalMs) {
        this.builder = builder;
        this.connectTimeout = connectTimeout;
        this.pollIntervalMs = Math.max(100, pollIntervalMs);
    }

    public ExecutionResult execute(String endpoint, String sql, int timeoutSeconds) {
        return execute(endpoint, sql, timeoutSeconds, null);
    }

    public ExecutionResult execute(String endpoint, String sql, int timeoutSeconds, String executionKey) {
        if (endpoint == null || endpoint.isBlank()) throw new FlinkExecutionException("Flink SQL Gateway 地址不能为空");
        if (sql == null || sql.isBlank()) throw new FlinkExecutionException("Flink SQL 不能为空");
        List<String> statements = FlinkSqlScript.statements(sql, false);
        RestClient client = client(endpoint);
        String session = null;
        String operation = null;
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> sessionBody = client.post().uri("/v1/sessions")
                    .body(Map.of()).retrieve().body(Map.class);
            session = text(sessionBody, "sessionHandle", "sessionId");
            if (session == null) throw new FlinkExecutionException("Flink SQL Gateway 未返回会话句柄");
            runSetupStatements(client, session, statements);

            Map<String, Object> statementBody = client.post()
                    .uri("/v1/sessions/{session}/statements", session)
                    .body(Map.of("statement", statements.getLast())).retrieve().body(Map.class);
            operation = text(statementBody, "operationHandle", "operationId");
            if (operation == null) throw new FlinkExecutionException("Flink SQL Gateway 未返回作业句柄");
            if (executionKey != null && !executionKey.isBlank()) {
                activeOperations.put(executionKey, new ActiveOperation(endpoint, session, operation));
            }

            long deadline = System.currentTimeMillis() + Math.max(1, timeoutSeconds) * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> statusBody = client.get()
                        .uri("/v1/sessions/{session}/operations/{operation}/status", session, operation)
                        .retrieve().body(Map.class);
                String status = status(statusBody);
                if ("FINISHED".equals(status) || "CANCELED".equals(status)) {
                    if ("CANCELED".equals(status)) throw new FlinkExecutionException("Flink SQL 作业已取消", true);
                    return new ExecutionResult(null, System.currentTimeMillis() - started, session, operation);
                }
                if ("ERROR".equals(status) || "FAILED".equals(status)) {
                    throw new FlinkExecutionException("Flink SQL 作业执行失败");
                }
                sleep(Math.min(pollIntervalMs, Math.max(100, deadline - System.currentTimeMillis())));
            }
            throw new FlinkExecutionException("Flink SQL 作业超时，句柄=" + operation);
        } catch (RestClientResponseException error) {
            log.warn("Flink SQL Gateway request failed: status={}", error.getStatusCode());
            throw new FlinkExecutionException("Flink SQL Gateway 请求失败（HTTP " + error.getStatusCode().value() + "）");
        } catch (FlinkExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("Flink SQL Gateway unavailable", error);
            throw new FlinkExecutionException("无法连接 Flink SQL Gateway");
        } finally {
            if (executionKey != null) activeOperations.remove(executionKey);
            if (session != null && operation != null) {
                try {
                    client.delete().uri("/v1/sessions/{session}/operations/{operation}/close", session, operation)
                            .retrieve().toBodilessEntity();
                } catch (RuntimeException closeError) {
                    log.debug("Could not close Flink SQL Gateway operation", closeError);
                }
            }
            if (session != null) {
                try { client.delete().uri("/v1/sessions/{session}", session).retrieve().toBodilessEntity(); }
                catch (RuntimeException closeError) { log.debug("Could not close Flink SQL Gateway session", closeError); }
            }
        }
    }

    /**
     * Submit a continuous INSERT and return as soon as SQL Gateway gives us a
     * session/operation handle. The session is intentionally kept open; the
     * handle is persisted by the workflow service and can be polled/cancelled
     * after the request thread and even the service process have gone away.
     */
    public ExecutionResult startStreaming(String endpoint, String sql, String executionKey) {
        if (endpoint == null || endpoint.isBlank()) throw new FlinkExecutionException("Flink SQL Gateway 地址不能为空");
        if (sql == null || sql.isBlank()) throw new FlinkExecutionException("Flink SQL 不能为空");
        List<String> statements = FlinkSqlScript.statements(sql, true);
        RestClient client = client(endpoint);
        String session = null;
        try {
            Map<String, Object> sessionBody = client.post().uri("/v1/sessions")
                    .body(Map.of()).retrieve().body(Map.class);
            session = text(sessionBody, "sessionHandle", "sessionId");
            if (session == null) throw new FlinkExecutionException("Flink SQL Gateway 未返回会话句柄");
            runSetupStatements(client, session, statements);
            Map<String, Object> statementBody = client.post()
                    .uri("/v1/sessions/{session}/statements", session)
                    .body(Map.of("statement", statements.getLast())).retrieve().body(Map.class);
            String operation = text(statementBody, "operationHandle", "operationId");
            if (operation == null) throw new FlinkExecutionException("Flink SQL Gateway 未返回作业句柄");
            if (executionKey != null && !executionKey.isBlank()) {
                activeOperations.put(executionKey, new ActiveOperation(endpoint, session, operation));
            }
            return new ExecutionResult(null, 0L, session, operation);
        } catch (RestClientResponseException error) {
            closeSession(client, session);
            throw new FlinkExecutionException("Flink SQL Gateway 请求失败（HTTP " + error.getStatusCode().value() + "）");
        } catch (FlinkExecutionException error) {
            closeSession(client, session);
            throw error;
        } catch (RuntimeException error) {
            closeSession(client, session);
            log.warn("Flink SQL Gateway unavailable while starting stream", error);
            throw new FlinkExecutionException("无法连接 Flink SQL Gateway");
        }
    }

    /**
     * Starts a new streaming SQL operation from a completed savepoint.
     *
     * <p>Flink has no generic REST "resume" operation. The safe equivalent is
     * a new SQL Gateway session with {@code execution.savepoint.path} applied
     * before the INSERT is submitted. The setup statement is waited to FINISHED
     * before the streaming statement is sent, and the session stays open only
     * after the new operation handle has been persisted by the caller.</p>
     */
    public ExecutionResult startStreamingFromSavepoint(
            String endpoint, String sql, String savepointPath, String executionKey) {
        if (endpoint == null || endpoint.isBlank()) throw new FlinkExecutionException("Flink SQL Gateway 地址不能为空");
        if (sql == null || sql.isBlank()) throw new FlinkExecutionException("Flink SQL 不能为空");
        List<String> statements = FlinkSqlScript.statements(sql, true);
        if (savepointPath == null || !savepointPath.matches("(?:s3|hdfs|file)://\\S{1,2048}")) {
            throw new FlinkExecutionException("savepoint 路径必须是 s3://、hdfs:// 或 file:// URL");
        }
        RestClient client = client(endpoint);
        String session = null;
        boolean keepSession = false;
        try {
            Map<String, Object> sessionBody = client.post().uri("/v1/sessions")
                    .body(Map.of()).retrieve().body(Map.class);
            session = text(sessionBody, "sessionHandle", "sessionId");
            if (session == null) throw new FlinkExecutionException("Flink SQL Gateway 未返回会话句柄");

            String setupOperation = submitStatement(client, session,
                    "SET 'execution.savepoint.path' = '" + escapeSqlLiteral(savepointPath) + "'");
            waitForFinished(client, session, setupOperation, SAVEPOINT_SETUP_TIMEOUT_MS);
            closeOperation(client, session, setupOperation);

            runSetupStatements(client, session, statements);
            String operation = submitStatement(client, session, statements.getLast());
            if (executionKey != null && !executionKey.isBlank()) {
                activeOperations.put(executionKey, new ActiveOperation(endpoint, session, operation));
            }
            keepSession = true;
            return new ExecutionResult(null, 0L, session, operation);
        } catch (RestClientResponseException error) {
            throw new FlinkExecutionException("Flink SQL Gateway 请求失败（HTTP " + error.getStatusCode().value() + "）");
        } catch (FlinkExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("Flink SQL Gateway unavailable while resuming stream", error);
            throw new FlinkExecutionException("无法从 savepoint 恢复 Flink 流任务");
        } finally {
            if (!keepSession) closeSession(client, session);
        }
    }

    /** Requests cancellation of an operation currently being polled by this client. */
    public boolean cancel(String executionKey) {
        ActiveOperation operation = activeOperations.get(executionKey);
        if (operation == null) return false;
        return cancelHandle(operation.endpoint(), operation.session(), operation.operation());
    }

    /** Cancel a persisted session/operation handle, not just an in-memory key. */
    public boolean cancelHandle(String endpoint, String session, String operation) {
        if (endpoint == null || session == null || operation == null) return false;
        try {
            client(endpoint).post()
                    .uri("/v1/sessions/{session}/operations/{operation}/cancel", session, operation)
                    .body(Map.of())
                    .retrieve().toBodilessEntity();
            activeOperations.values().removeIf(value -> value.endpoint().equals(endpoint)
                    && value.session().equals(session) && value.operation().equals(operation));
            return true;
        } catch (RuntimeException error) {
            log.warn("Flink SQL cancellation request failed", error);
            throw new FlinkExecutionException("Flink SQL 取消请求失败");
        }
    }

    public GatewayOperationStatus operationStatus(String endpoint, String session, String operation) {
        if (endpoint == null || session == null || operation == null) {
            throw new FlinkExecutionException("Flink SQL 远端句柄不完整");
        }
        try {
            Map<String, Object> body = client(endpoint).get()
                    .uri("/v1/sessions/{session}/operations/{operation}/status", session, operation)
                    .retrieve().body(Map.class);
            String value = status(body);
            return new GatewayOperationStatus(value, "FINISHED".equals(value) || "CANCELED".equals(value)
                    || "ERROR".equals(value) || "FAILED".equals(value));
        } catch (RestClientResponseException error) {
            throw new FlinkExecutionException("Flink SQL Gateway 请求失败（HTTP " + error.getStatusCode().value() + "）");
        } catch (RuntimeException error) {
            throw new FlinkExecutionException("无法查询 Flink SQL 远端状态");
        }
    }

    /** SQL Gateway INSERT result contains the JobManager identity after submission completes. */
    public String submittedJobId(String endpoint, String session, String operation) {
        if (endpoint == null || session == null || operation == null) {
            throw new FlinkExecutionException("Flink SQL 远端句柄不完整");
        }
        try {
            Map<?, ?> body = client(endpoint).get()
                    .uri("/v1/sessions/{session}/operations/{operation}/result/0?rowFormat=JSON", session, operation)
                    .retrieve().body(Map.class);
            Object value = body == null ? null : body.get("jobID");
            String id = value == null ? null : String.valueOf(value);
            if (id == null || !JOB_ID.matcher(id).matches()) return null;
            return id.toLowerCase(java.util.Locale.ROOT);
        } catch (RestClientResponseException error) {
            throw new FlinkExecutionException("Flink SQL Job ID 查询失败（HTTP " + error.getStatusCode().value() + "）");
        } catch (RuntimeException error) {
            if (error instanceof FlinkExecutionException) throw error;
            throw new FlinkExecutionException("无法查询 Flink SQL Job ID");
        }
    }

    public GatewayHealth health(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new FlinkExecutionException("Flink SQL Gateway 地址不能为空");
        try {
            Map<String, Object> body = client(endpoint).get().uri("/v1/info").retrieve().body(Map.class);
            String version = text(body, "version", "flinkVersion");
            return new GatewayHealth("UP", version, endpoint.replaceAll("/+$", ""));
        } catch (RestClientResponseException error) {
            throw new FlinkExecutionException("Flink SQL Gateway 请求失败（HTTP " + error.getStatusCode().value() + "）");
        } catch (RuntimeException error) {
            throw new FlinkExecutionException("无法连接 Flink SQL Gateway");
        }
    }

    private RestClient client(String endpoint) {
        // Keep the shared builder's request factory (and test interceptors) intact;
        // the Gateway operation deadline is enforced by polling rather than a short
        // global read timeout, because a statement may legitimately run for hours.
        return builder.baseUrl(endpoint.replaceAll("/+$", "")).build();
    }

    private String submitStatement(RestClient client, String session, String statement) {
        Map<String, Object> body = client.post()
                .uri("/v1/sessions/{session}/statements", session)
                .body(Map.of("statement", statement)).retrieve().body(Map.class);
        String operation = text(body, "operationHandle", "operationId");
        if (operation == null) throw new FlinkExecutionException("Flink SQL Gateway 未返回作业句柄");
        return operation;
    }

    private void runSetupStatements(RestClient client, String session, List<String> statements) {
        for (int index = 0; index < statements.size() - 1; index++) {
            String operation = submitStatement(client, session, statements.get(index));
            try { waitForFinished(client, session, operation, SAVEPOINT_SETUP_TIMEOUT_MS); }
            finally { closeOperation(client, session, operation); }
        }
    }

    private void waitForFinished(RestClient client, String session, String operation, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> body = client.get()
                    .uri("/v1/sessions/{session}/operations/{operation}/status", session, operation)
                    .retrieve().body(Map.class);
            String operationStatus = status(body);
            if ("FINISHED".equals(operationStatus)) return;
            if ("ERROR".equals(operationStatus) || "FAILED".equals(operationStatus)
                    || "CANCELED".equals(operationStatus)) {
                throw new FlinkExecutionException("Flink savepoint 恢复配置语句执行失败，状态=" + operationStatus);
            }
            sleep(Math.min(pollIntervalMs, Math.max(100, deadline - System.currentTimeMillis())));
        }
        throw new FlinkExecutionException("Flink savepoint 恢复配置语句超时");
    }

    private void closeOperation(RestClient client, String session, String operation) {
        try {
            client.delete().uri("/v1/sessions/{session}/operations/{operation}/close", session, operation)
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException closeError) {
            log.debug("Could not close Flink setup operation", closeError);
        }
    }

    private String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }

    @SuppressWarnings("unchecked")
    private String status(Map<String, Object> body) {
        if (body == null) return "";
        Object nested = body.get("status");
        if (nested instanceof Map<?, ?> map) {
            Object type = map.get("type");
            return String.valueOf(type == null ? "" : type).toUpperCase();
        }
        return String.valueOf(nested == null ? body.getOrDefault("type", "") : nested).toUpperCase();
    }

    private String text(Map<String, Object> body, String... keys) {
        if (body == null) return null;
        for (String key : keys) if (body.get(key) != null && !String.valueOf(body.get(key)).isBlank()) return String.valueOf(body.get(key));
        return null;
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new FlinkExecutionException("Flink SQL 等待被中断"); }
    }

    private void closeSession(RestClient client, String session) {
        if (client == null || session == null) return;
        try { client.delete().uri("/v1/sessions/{session}", session).retrieve().toBodilessEntity(); }
        catch (RuntimeException closeError) { log.debug("Could not close failed Flink SQL session", closeError); }
    }

    public record ExecutionResult(Long affectedRows, Long durationMs, String sessionHandle, String operationHandle) { }

    public record GatewayHealth(String status, String version, String endpoint) { }

    public record GatewayOperationStatus(String status, boolean terminal) { }

    private record ActiveOperation(String endpoint, String session, String operation) { }

    public static class FlinkExecutionException extends RuntimeException {
        private final boolean cancelled;
        public FlinkExecutionException(String message) { this(message, false); }
        public FlinkExecutionException(String message, boolean cancelled) { super(message); this.cancelled = cancelled; }
        public boolean cancelled() { return cancelled; }
    }
}
