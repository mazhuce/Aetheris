package com.aetheris.workflow.service;

import com.aetheris.workflow.service.ExecutionAdapter.AdapterHealth;
import com.aetheris.workflow.service.ExecutionAdapter.ArtifactInfo;
import com.aetheris.workflow.service.ExecutionAdapter.LogPage;
import com.aetheris.workflow.service.ExecutionAdapter.ManagedHandle;
import com.aetheris.workflow.service.ExecutionAdapter.RemoteExecution;
import com.aetheris.workflow.service.ExecutionAdapter.Submission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP adapter for the authenticated WSL local spark-submit runner. */
@Component
public class SparkBatchExecutionAdapter implements ExecutionAdapter {

    public static final String DEFAULT_RUNNER = "config://default";

    private final RestClient defaultClient;
    private final RestClient.Builder builder;
    private final String defaultBaseUrl;
    private final String token;
    private final Duration connectTimeout;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();
    private final boolean enabled;

    @Autowired
    public SparkBatchExecutionAdapter(
            RestClient.Builder builder,
            @Value("${aetheris.spark-runner.base-url:http://127.0.0.1:18081}") String baseUrl,
            @Value("${aetheris.spark-runner.token:}") String token,
            @Value("${aetheris.spark-runner.enabled:false}") boolean enabled,
            @Value("${aetheris.spark-runner.connect-timeout:PT3S}") Duration connectTimeout) {
        if (enabled && (token == null || token.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalStateException("AETHERIS_SPARK_RUNNER_TOKEN must contain at least 32 UTF-8 bytes");
        }
        this.builder = builder;
        this.defaultBaseUrl = normalizeBaseUrl(baseUrl);
        this.token = token == null ? "" : token;
        this.connectTimeout = connectTimeout;
        this.defaultClient = buildClient(this.defaultBaseUrl);
        this.enabled = enabled;
    }

    SparkBatchExecutionAdapter(RestClient client, boolean enabled) {
        this.defaultClient = client;
        this.builder = null;
        this.defaultBaseUrl = "http://127.0.0.1";
        this.token = "";
        this.connectTimeout = Duration.ofSeconds(3);
        this.enabled = enabled;
    }

    @Override
    public ArtifactInfo validate(String artifactKey, String expectedDigest) {
        return validate(DEFAULT_RUNNER, artifactKey, expectedDigest);
    }

    public ArtifactInfo validate(String runnerBaseUrl, String artifactKey, String expectedDigest) {
        requireEnabled();
        RestClient client = clientFor(runnerBaseUrl);
        ArtifactInfo actual = call(() -> client.get().uri("/v1/artifacts/{key}", artifactKey)
                .retrieve().body(ArtifactInfo.class), false);
        if (actual == null || !expectedDigest.equals(actual.sha256())) {
            throw new SparkRunnerException("受管制品摘要与运行器文件不一致", 409, false);
        }
        return actual;
    }

    @Override
    public ManagedHandle submit(Submission submission) {
        return submit(DEFAULT_RUNNER, submission);
    }

    public ManagedHandle submit(String runnerBaseUrl, Submission submission) {
        requireEnabled();
        RestClient client = clientFor(runnerBaseUrl);
        Map<String, Object> body = Map.of(
                "requestKey", submission.requestKey(),
                "artifactKey", submission.artifactKey(),
                "artifactDigest", submission.artifactDigest(),
                "parameters", submission.parameters(),
                "resources", Map.of(
                        "cores", submission.cores(),
                        "driverMemoryMb", submission.driverMemoryMb(),
                        "timeoutSeconds", submission.timeoutSeconds()));
        try {
            ManagedHandle handle = client.post().uri("/v1/jobs").body(body)
                    .retrieve().body(ManagedHandle.class);
            if (handle == null || handle.id() == null) {
                throw new SparkRunnerException("Spark 运行器返回空句柄", 502, true);
            }
            return handle;
        } catch (RestClientResponseException error) {
            throw response(error, false);
        } catch (SparkRunnerException error) {
            throw error;
        } catch (RuntimeException uncertain) {
            // POST may already have reached the runner. Resolve the idempotency key
            // before deciding whether this is unknown; never submit a second job here.
            try {
                return reconcile(submission.requestKey()).orElseThrow(() ->
                        new SparkRunnerException("Spark 提交结果未知，等待恢复核对", 503, true));
            } catch (SparkRunnerException recoveryFailure) {
                throw new SparkRunnerException("Spark 提交结果未知，等待恢复核对", 503, true);
            }
        }
    }

    @Override
    public RemoteExecution status(String remoteHandle) {
        return status(DEFAULT_RUNNER, remoteHandle);
    }

    public RemoteExecution status(String runnerBaseUrl, String remoteHandle) {
        requireEnabled();
        RestClient client = clientFor(runnerBaseUrl);
        return call(() -> client.get().uri("/v1/jobs/{id}", remoteHandle)
                .retrieve().body(RemoteExecution.class), false);
    }

    @Override
    public LogPage logs(String remoteHandle, long offset, int limit) {
        return logs(DEFAULT_RUNNER, remoteHandle, offset, limit);
    }

    public LogPage logs(String runnerBaseUrl, String remoteHandle, long offset, int limit) {
        requireEnabled();
        RestClient client = clientFor(runnerBaseUrl);
        int boundedLimit = Math.clamp(limit, 1, 65_536);
        return call(() -> client.get().uri(builder -> builder.path("/v1/jobs/{id}/logs")
                        .queryParam("offset", Math.max(0, offset))
                        .queryParam("limit", boundedLimit).build(remoteHandle))
                .retrieve().body(LogPage.class), false);
    }

    @Override
    public RemoteExecution cancel(String remoteHandle) {
        return cancel(DEFAULT_RUNNER, remoteHandle);
    }

    public RemoteExecution cancel(String runnerBaseUrl, String remoteHandle) {
        requireEnabled();
        RestClient client = clientFor(runnerBaseUrl);
        return call(() -> client.delete().uri("/v1/jobs/{id}", remoteHandle)
                .retrieve().body(RemoteExecution.class), false);
    }

    @Override
    public Optional<ManagedHandle> reconcile(String requestKey) {
        return reconcile(DEFAULT_RUNNER, requestKey);
    }

    public Optional<ManagedHandle> reconcile(String runnerBaseUrl, String requestKey) {
        requireEnabled();
        RestClient client = clientFor(runnerBaseUrl);
        try {
            return Optional.ofNullable(client.get().uri("/v1/jobs/by-request/{key}", requestKey)
                    .retrieve().body(ManagedHandle.class));
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) return Optional.empty();
            throw response(error, false);
        } catch (RuntimeException error) {
            throw new SparkRunnerException("无法连接 Spark 运行器", 503, false);
        }
    }

    @Override
    public AdapterHealth health() {
        return health(DEFAULT_RUNNER);
    }

    public AdapterHealth health(String runnerBaseUrl) {
        if (!enabled) return new AdapterHealth("DISABLED", null, 0, 0);
        RestClient client = clientFor(runnerBaseUrl);
        return call(() -> client.get().uri("/health").retrieve().body(AdapterHealth.class), false);
    }

    /** Validates and canonicalizes administrator-provided runner endpoints. */
    static String normalizeBaseUrl(String value) {
        if (DEFAULT_RUNNER.equals(value)) return value;
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("Runner 地址必须是无凭据、无路径的 HTTP(S) 基础地址");
            }
            return new URI(uri.getScheme().toLowerCase(), null, uri.getHost().toLowerCase(),
                    uri.getPort(), null, null, null).toString();
        } catch (java.net.URISyntaxException | IllegalArgumentException error) {
            throw new IllegalArgumentException("Runner 地址必须是无凭据、无路径的 HTTP(S) 基础地址");
        }
    }

    private RestClient clientFor(String runnerBaseUrl) {
        if (DEFAULT_RUNNER.equals(runnerBaseUrl)) return defaultClient;
        String normalized = normalizeBaseUrl(runnerBaseUrl);
        if (normalized.equals(defaultBaseUrl)) return defaultClient;
        if (builder == null) throw new IllegalStateException("测试适配器未配置多 Runner 客户端");
        return clients.computeIfAbsent(normalized, this::buildClient);
    }

    private RestClient buildClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout(15_000);
        return builder.clone().requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Aetheris-Runner-Token", token).build();
    }

    private <T> T call(java.util.function.Supplier<T> operation, boolean submit) {
        try {
            T result = operation.get();
            if (result == null) throw new SparkRunnerException("Spark 运行器返回空响应", 502, submit);
            return result;
        } catch (RestClientResponseException error) {
            throw response(error, submit);
        } catch (SparkRunnerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new SparkRunnerException("无法连接 Spark 运行器", 503, submit);
        }
    }

    private SparkRunnerException response(RestClientResponseException error, boolean submit) {
        String message = "Spark 运行器拒绝请求（HTTP " + error.getStatusCode().value() + "）";
        try {
            Map<?, ?> body = error.getResponseBodyAs(Map.class);
            if (body != null && body.get("message") instanceof String safe && !safe.isBlank()) {
                message = safe;
            }
        } catch (RuntimeException ignored) {
            // Keep the bounded status message.
        }
        HttpStatusCode status = error.getStatusCode();
        return new SparkRunnerException(message, status.value(), submit && status.is5xxServerError());
    }

    private void requireEnabled() {
        if (!enabled) throw new SparkRunnerException("受管 Spark 运行器未启用", 503, false);
    }
}
