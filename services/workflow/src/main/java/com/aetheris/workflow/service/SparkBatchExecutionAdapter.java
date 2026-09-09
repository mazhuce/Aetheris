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
import java.util.Map;
import java.util.Optional;

/** HTTP adapter for the authenticated WSL local spark-submit runner. */
@Component
public class SparkBatchExecutionAdapter implements ExecutionAdapter {

    private final RestClient client;
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
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout(15_000);
        this.client = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Aetheris-Runner-Token", token == null ? "" : token).build();
        this.enabled = enabled;
    }

    SparkBatchExecutionAdapter(RestClient client, boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    @Override
    public ArtifactInfo validate(String artifactKey, String expectedDigest) {
        requireEnabled();
        ArtifactInfo actual = call(() -> client.get().uri("/v1/artifacts/{key}", artifactKey)
                .retrieve().body(ArtifactInfo.class), false);
        if (actual == null || !expectedDigest.equals(actual.sha256())) {
            throw new SparkRunnerException("受管制品摘要与运行器文件不一致", 409, false);
        }
        return actual;
    }

    @Override
    public ManagedHandle submit(Submission submission) {
        requireEnabled();
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
        requireEnabled();
        return call(() -> client.get().uri("/v1/jobs/{id}", remoteHandle)
                .retrieve().body(RemoteExecution.class), false);
    }

    @Override
    public LogPage logs(String remoteHandle, long offset, int limit) {
        requireEnabled();
        int boundedLimit = Math.clamp(limit, 1, 65_536);
        return call(() -> client.get().uri(builder -> builder.path("/v1/jobs/{id}/logs")
                        .queryParam("offset", Math.max(0, offset))
                        .queryParam("limit", boundedLimit).build(remoteHandle))
                .retrieve().body(LogPage.class), false);
    }

    @Override
    public RemoteExecution cancel(String remoteHandle) {
        requireEnabled();
        return call(() -> client.delete().uri("/v1/jobs/{id}", remoteHandle)
                .retrieve().body(RemoteExecution.class), false);
    }

    @Override
    public Optional<ManagedHandle> reconcile(String requestKey) {
        requireEnabled();
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
        if (!enabled) return new AdapterHealth("DISABLED", null, 0, 0);
        return call(() -> client.get().uri("/health").retrieve().body(AdapterHealth.class), false);
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
