package com.aetheris.workflow.service;

import com.aetheris.workflow.service.ExecutionAdapter.AdapterHealth;
import com.aetheris.workflow.service.ExecutionAdapter.ArtifactInfo;
import com.aetheris.workflow.service.ExecutionAdapter.LogPage;
import com.aetheris.workflow.service.ExecutionAdapter.ManagedHandle;
import com.aetheris.workflow.service.ExecutionAdapter.RemoteExecution;
import com.aetheris.workflow.service.ExecutionAdapter.Submission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;

import java.time.Duration;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP adapter for the authenticated WSL local spark-submit runner. */
@Component
public class SparkBatchExecutionAdapter implements ExecutionAdapter {

    public static final String DEFAULT_RUNNER = "config://default";
    private static final int MAX_HANDLE_BYTES = 64 * 1024;
    private static final java.util.Set<Integer> PRE_EXECUTION_REJECTIONS = java.util.Set.of(400,401,403,404,405,413,415,422);
    private static final java.util.Set<String> HANDLE_STATES = java.util.Set.of(
            "queued","submitting","running","cancel_requested","succeeded","failed","cancelled","timed_out","unknown");
    private static final ObjectMapper HANDLE_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxNumberLength(20).maxStringLength(MAX_HANDLE_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final RestClient defaultClient;
    private final RestClient.Builder builder;
    private final String defaultBaseUrl;
    private final String token;
    private final Duration connectTimeout;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final OutboundTargetPolicy targetPolicy;
    private final java.util.Set<String> allowedOrigins;

    @Autowired
    public SparkBatchExecutionAdapter(
            RestClient.Builder builder,
            @Value("${aetheris.spark-runner.base-url:http://127.0.0.1:18081}") String baseUrl,
            @Value("${aetheris.spark-runner.token:}") String token,
            @Value("${aetheris.spark-runner.enabled:false}") boolean enabled,
            @Value("${aetheris.spark-runner.connect-timeout:PT3S}") Duration connectTimeout,
            OutboundTargetPolicy targetPolicy,
            @Value("${aetheris.spark-runner.allowed-origins:}") String additionalOrigins) {
        if (enabled && (token == null || token.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalStateException("AETHERIS_SPARK_RUNNER_TOKEN must contain at least 32 UTF-8 bytes");
        }
        this.builder = builder;
        this.targetPolicy = targetPolicy;
        this.defaultBaseUrl = normalizeBaseUrl(baseUrl);
        var origins = new java.util.HashSet<String>();
        origins.add(this.defaultBaseUrl);
        for (String origin : additionalOrigins.split(",")) {
            if (!origin.isBlank()) origins.add(normalizeBaseUrl(origin.trim()));
        }
        this.allowedOrigins = java.util.Set.copyOf(origins);
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
        this.targetPolicy = new OutboundTargetPolicy();
        this.allowedOrigins = java.util.Set.of(this.defaultBaseUrl);
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

    /** Uploads a local PySpark or Spark JAR artifact to the runner's immutable artifact directory. */
    public ArtifactInfo upload(String runnerBaseUrl, String artifactKey, byte[] content) {
        requireEnabled();
        if (content == null || content.length == 0 || content.length > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("Spark 制品大小必须在 1B 到 64MB 之间");
        }
        final String digest;
        try {
            digest = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
        RestClient client = clientFor(runnerBaseUrl);
        return call(() -> client.post().uri("/v1/artifacts/{key}", artifactKey)
                .body(Map.of("artifactKey", artifactKey, "sha256", digest,
                        "contentBase64", java.util.Base64.getEncoder().encodeToString(content)))
                .retrieve().body(ArtifactInfo.class), false);
    }

    @Override
    public ManagedHandle submit(Submission submission) {
        return submit(DEFAULT_RUNNER, submission);
    }

    public ManagedHandle submit(String runnerBaseUrl, Submission submission) {
        requireEnabled();
        RestClient client = clientFor(runnerBaseUrl);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("requestKey", submission.requestKey());
        body.put("artifactKey", submission.artifactKey());
        body.put("artifactType", submission.artifactType());
        body.put("artifactDigest", submission.artifactDigest());
        if (submission.mainClass() != null && !submission.mainClass().isBlank()) {
            body.put("mainClass", submission.mainClass());
        }
        body.put("parameters", submission.parameters());
        body.put("resources", Map.of(
                "cores", submission.cores(),
                "driverMemoryMb", submission.driverMemoryMb(),
                "timeoutSeconds", submission.timeoutSeconds()));
        try {
            return requireArtifact(managed(client.post().uri("/v1/jobs").body(body), submission.requestKey(), true),
                    submission.artifactDigest());
        } catch (RuntimeException uncertain) {
            if (uncertain instanceof SparkRunnerException known && !known.isSubmissionOutcomeUnknown()) throw known;
            // Every ambiguous response is read back only at the original runner.
            // A 404 is still uncertainty, never permission to send a second POST.
            try {
                ManagedHandle recovered = reconcile(runnerBaseUrl, submission.requestKey()).orElse(null);
                if (recovered != null) return requireArtifact(recovered, submission.artifactDigest());
            } catch (RuntimeException recoveryFailure) {
                throw new SparkRunnerException("Spark 提交结果未知，等待恢复核对", 503, true);
            }
            throw new SparkRunnerException("Spark 提交结果未知，等待恢复核对", 503, true);
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
    /** Sealed sources accept status only for their original key, handle and artifact. */
    public RemoteExecution statusBound(String origin,String handle,String key,String digest){
        requireEnabled();return remoteBound(clientFor(origin).get().uri("/v1/jobs/{id}",handle),handle,key,digest);
    }
    public RemoteExecution cancelBound(String origin,String handle,String key,String digest){
        requireEnabled();return remoteBound(clientFor(origin).delete().uri("/v1/jobs/{id}",handle),handle,key,digest);
    }
    private RemoteExecution remoteBound(RestClient.RequestHeadersSpec<?> request,String handle,String key,String digest){
        JsonNode n=bounded(request,MAX_HANDLE_BYTES);requireBinding(n,handle,key,digest);
        String state=handleText(n,"status");if(!HANDLE_STATES.contains(state))throw invalidHandle();
        JsonNode count=n.get("affectedRows");Long rows=count==null||count.isNull()?null:
            count.isIntegralNumber()&&count.canConvertToLong()&&count.longValue()>=0?count.longValue():null;
        if(count!=null&&!count.isNull()&&rows==null)throw invalidHandle();
        String cancelled=nullableText(n,"cancellationConfirmedAt");
        if("cancelled".equals(state)||"timed_out".equals(state)){
            if(cancelled==null)throw invalidHandle();
            try{if(java.time.OffsetDateTime.parse(cancelled).isAfter(java.time.OffsetDateTime.now().plusSeconds(30)))throw invalidHandle();}
            catch(java.time.format.DateTimeParseException bad){throw invalidHandle();}
        }
        JsonNode result=n.get("result");if(result!=null&&!result.isNull()&&!result.isObject())throw invalidHandle();
        Map<String,Object> values=result==null||result.isNull()?Map.of():HANDLE_JSON.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        String app=nullableText(n,"applicationId");if(app!=null&&!app.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"))throw invalidHandle();
        return new RemoteExecution(handle,state,app,rows,nullableText(n,"errorCode"),null,values,cancelled);
    }
    public LogPage logsBound(String origin,String handle,String key,String digest,long offset,int limit){
        requireEnabled();int size=Math.clamp(limit,1,32768);long start=Math.max(0,offset);
        JsonNode n=bounded(clientFor(origin).get().uri(b->b.path("/v1/jobs/{id}/logs").queryParam("offset",start).queryParam("limit",size).build(handle)),256*1024);
        requireBinding(n,handle,key,digest);String content=handleText(n,"content");var next=n.get("nextOffset");var eof=n.get("eof");
        if(content.length()>size*3||next==null||!next.isIntegralNumber()||!next.canConvertToLong()||next.longValue()<start
            ||next.longValue()>start+size||eof==null||!eof.isBoolean())throw invalidHandle();
        return new LogPage(content,next.longValue(),eof.booleanValue());
    }
    private static String nullableText(JsonNode n,String field){return !n.hasNonNull(field)?null:handleText(n,field);}
    private static void requireBinding(JsonNode n,String handle,String key,String digest){
        if(!handle.equals(handleText(n,"id"))||!key.equals(handleText(n,"requestKey"))||!digest.equals(handleText(n,"artifactDigest")))throw invalidHandle();
    }
    private JsonNode bounded(RestClient.RequestHeadersSpec<?> request,int max){
        try{return request.exchange((out,response)->{
            if(!response.getStatusCode().is2xxSuccessful())throw runnerResponse(response.getStatusCode().value(),false);
            byte[] raw=response.getBody().readNBytes(max+1);if(raw.length==0||raw.length>max)throw invalidHandle();
            JsonNode n=HANDLE_JSON.readTree(raw);if(n==null||!n.isObject())throw invalidHandle();return n;});
        }catch(SparkRunnerException safe){throw safe;}catch(RuntimeException bad){throw invalidHandle();}
    }

    @Override
    public Optional<ManagedHandle> reconcile(String requestKey) {
        return reconcile(DEFAULT_RUNNER, requestKey);
    }

    public Optional<ManagedHandle> reconcile(String runnerBaseUrl, String requestKey) {
        requireEnabled();
        RestClient client = clientFor(runnerBaseUrl);
        try {
            return Optional.of(managed(client.get().uri("/v1/jobs/by-request/{key}", requestKey), requestKey, false));
        } catch (SparkRunnerException error) {
            if (error.getStatusCode() == 404) return Optional.empty();
            throw error;
        } catch (RuntimeException error) {
            throw new SparkRunnerException("无法连接 Spark 运行器", 503, false);
        }
    }

    private ManagedHandle managed(RestClient.RequestHeadersSpec<?> request, String requestKey, boolean submit) {
        return request.exchange((outgoing, response) -> {
            int status = response.getStatusCode().value();
            if (status < 200 || status >= 300) throw runnerResponse(status, submit);
            byte[] raw = response.getBody().readNBytes(MAX_HANDLE_BYTES + 1);
            if (raw.length == 0 || raw.length > MAX_HANDLE_BYTES) throw invalidHandle();
            JsonNode value = HANDLE_JSON.readTree(raw);
            if (value == null || !value.isObject()) throw invalidHandle();
            String id = handleText(value,"id"), key = handleText(value,"requestKey");
            String state = handleText(value,"status"), digest = handleText(value,"artifactDigest");
            if (!id.matches("[a-f0-9]{32}") || !key.equals(requestKey)
                    || !HANDLE_STATES.contains(state) || !digest.matches("[a-f0-9]{64}")) throw invalidHandle();
            JsonNode app = value.get("applicationId");
            String applicationId = app == null || app.isNull() ? null : handleText(value,"applicationId");
            if (applicationId != null && !applicationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) throw invalidHandle();
            return new ManagedHandle(id,key,state,applicationId,digest);
        });
    }
    private static String handleText(JsonNode n,String key) {
        JsonNode v=n.get(key); if(v==null||!v.isTextual())throw invalidHandle(); return v.textValue();
    }
    private static ManagedHandle requireArtifact(ManagedHandle handle,String digest) {
        if(!handle.artifactDigest().equals(digest))throw invalidHandle(); return handle;
    }
    private static SparkRunnerException invalidHandle() {
        return new SparkRunnerException("Spark 运行器返回的句柄无法核实",502,true);
    }
    private static SparkRunnerException runnerResponse(int status,boolean submit) {
        return new SparkRunnerException("Spark 运行器请求未获确认（HTTP "+status+"）",status,
                submit && !PRE_EXECUTION_REJECTIONS.contains(status));
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
    public static String normalizeBaseUrl(String value) {
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
        if (!allowedOrigins.contains(normalized)) throw new IllegalArgumentException("Runner 未列入受信任基础地址配置");
        if (normalized.equals(defaultBaseUrl)) return defaultClient;
        if (builder == null) throw new IllegalStateException("测试适配器未配置多 Runner 客户端");
        targetPolicy.httpOrigin(normalized);
        synchronized (clients) {
            if (!clients.containsKey(normalized) && clients.size() >= 64)
                throw new IllegalArgumentException("Runner 客户端数量超过服务上限");
            return clients.computeIfAbsent(normalized, this::buildClient);
        }
    }

    private RestClient buildClient(String baseUrl) {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(15_000);
        return builder.clone().requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Aetheris-Runner-Token", token)
                .requestInterceptor((request, body, execution) -> {
                    targetPolicy.requireHost(request.getURI().getHost());
                    var response = execution.execute(request, body);
                    if (response.getStatusCode().is3xxRedirection()) {
                        response.close();
                        throw new IllegalArgumentException("计算引擎重定向被拒绝");
                    }
                    return response;
                }).build();
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
        return runnerResponse(error.getStatusCode().value(), submit);
    }

    private void requireEnabled() {
        if (!enabled) throw new SparkRunnerException("受管 Spark 运行器未启用", 503, false);
    }
}
