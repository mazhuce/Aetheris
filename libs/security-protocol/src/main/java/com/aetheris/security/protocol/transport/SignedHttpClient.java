package com.aetheris.security.protocol.transport;

import com.aetheris.security.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed-destination, authenticated raw-byte transport. No replay or business authorization store. */
public final class SignedHttpClient implements AutoCloseable {
    public static final String REQUEST_PROOF = "X-Aetheris-Workload-Proof";
    public static final String USER_PROOF = "X-Aetheris-User-Proof";
    public static final String RESPONSE_PROOF = "X-Aetheris-Response-Proof";
    private static final int HARD_BODY_LIMIT = 16 * 1024 * 1024;
    private static final ProxySelector DIRECT = new ProxySelector() {
        public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
    };

    public enum Failure { INVALID_REQUEST, UNTRUSTED_RESPONSE, TOO_LARGE, DEADLINE, IO, CLOSED, BUSY }
    public static final class TransportException extends RuntimeException {
        private final Failure failure;
        private TransportException(Failure failure, String message) { super(message); this.failure = failure; }
        public Failure failure() { return failure; }
    }
    public record Limits(int requestBytes, int responseBytes, int maxInFlight,
                         Duration connectTimeout, Duration totalDeadline) {
        public Limits {
            if (requestBytes < 0 || requestBytes > HARD_BODY_LIMIT || responseBytes < 0 || responseBytes > HARD_BODY_LIMIT
                    || maxInFlight < 1 || maxInFlight > 256 || connectTimeout == null || totalDeadline == null
                    || connectTimeout.isNegative() || connectTimeout.isZero() || totalDeadline.isNegative()
                    || totalDeadline.isZero() || totalDeadline.compareTo(Duration.ofSeconds(60)) > 0
                    || connectTimeout.compareTo(totalDeadline) > 0)
                throw failure(Failure.INVALID_REQUEST, "Invalid transport limits");
        }
        public static Limits defaults() { return new Limits(1024 * 1024, 1024 * 1024, 32, Duration.ofSeconds(2), Duration.ofSeconds(10)); }
    }

    /** Body is copied on construction and access; there is deliberately no URL/header-map API. */
    public static final class Request {
        private final String audience, method, path, operation, userProof;
        private final byte[] body;
        public Request(String audience, String method, String path, String operation, byte[] body) {
            this(audience, method, path, operation, body, null);
        }
        public Request(String audience, String method, String path, String operation, byte[] body, String userProof) {
            if (audience == null || method == null || path == null || operation == null || body == null)
                throw failure(Failure.INVALID_REQUEST, "Incomplete transport request");
            if (body.length > HARD_BODY_LIMIT) throw failure(Failure.TOO_LARGE, "Request body limit exceeded");
            if (userProof != null && (userProof.isEmpty() || userProof.length() > 12288
                    || userProof.chars().anyMatch(c -> c < 33 || c > 126)))
                throw failure(Failure.INVALID_REQUEST, "Invalid explicit user proof");
            this.audience = audience; this.method = method; this.path = path; this.operation = operation;
            this.body = body.clone(); this.userProof = userProof;
        }
        public String audience() { return audience; }
        public String method() { return method; }
        public String path() { return path; }
        public String operation() { return operation; }
        public byte[] body() { return body.clone(); }
        @Override public String toString() { return "SignedHttpRequest[redacted]"; }
    }

    /** Only constructed after signature, raw body, HTTP status and original request binding verification. */
    public static final class Response {
        private final int status;
        private final byte[] body;
        private final ProtocolVerifier.Verified<WorkloadResponseProof> verified;
        private Response(int status, byte[] body, ProtocolVerifier.Verified<WorkloadResponseProof> verified) {
            this.status = status; this.body = body.clone(); this.verified = verified;
        }
        public int status() { return status; }
        public byte[] body() { return body.clone(); }
        public ProtocolVerifier.Verified<WorkloadResponseProof> verified() { return verified; }
        public <T> T readJson(ObjectMapper mapper, Class<T> type) throws IOException { return mapper.readValue(body, type); }
        @Override public String toString() { return "SignedHttpResponse[status=" + status + ",bytes=" + body.length + "]"; }
    }

    private final String caller;
    private final ProtocolSigner signer;
    private final ProtocolVerifier verifier;
    private final Clock clock;
    private final TimePolicy policy;
    private final Map<String, URI> origins;
    private final Map<String, Set<String>> operations;
    private final Limits limits;
    private final ExecutorService executor;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Semaphore slots;
    private final Set<CompletableFuture<Response>> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public SignedHttpClient(String caller, ProtocolSigner signer, ProtocolVerifier verifier, Clock clock,
                            TimePolicy policy, Map<String, URI> origins, Map<String, Set<String>> operations, Limits limits) {
        requireNoRetryStartup();
        if (caller == null || !caller.matches("[a-z][a-z0-9-]{0,63}") || signer == null || verifier == null
                || clock == null || policy == null || limits == null || origins == null || origins.isEmpty()
                || operations == null || !origins.keySet().equals(operations.keySet()))
            throw failure(Failure.INVALID_REQUEST, "Incomplete transport configuration");
        var endpoints = new HashMap<String, URI>(); var acl = new HashMap<String, Set<String>>();
        origins.forEach((audience, origin) -> {
            if (audience == null || !audience.matches("[a-z][a-z0-9-]{0,63}") || origin == null
                    || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                    || origin.getHost() == null || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                    || origin.getPort() == 0 || origin.getPort() > 65535)
                throw failure(Failure.INVALID_REQUEST, "Registry entry must be a fixed HTTP origin");
            Set<String> allowed = operations.get(audience);
            if (allowed == null || allowed.isEmpty() || allowed.stream().anyMatch(op -> op == null || !op.matches("[a-z][a-z0-9._-]{0,95}")))
                throw failure(Failure.INVALID_REQUEST, "Invalid operation allowset");
            String text = origin.toASCIIString();
            endpoints.put(audience, URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text));
            acl.put(audience, Set.copyOf(allowed));
        });
        this.caller = caller; this.signer = signer; this.verifier = verifier; this.clock = clock; this.policy = policy;
        this.origins = Map.copyOf(endpoints); this.operations = Map.copyOf(acl); this.limits = limits;
        this.slots = new Semaphore(limits.maxInFlight());
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("aetheris-signed-http-", 0).factory());
        this.deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "aetheris-signed-http-deadline"); thread.setDaemon(true); return thread;
        });
        this.deadlines.setRemoveOnCancelPolicy(true);
    }

    private static void requireNoRetryStartup() {
        // MultiExchange caches these properties in static finals. A late setProperty cannot establish this guarantee.
        String name = "jdk.httpclient.disableRetryConnect";
        boolean startup = ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-D" + name + "=true");
        String attempts = "jdk.httpclient.redirects.retrylimit";
        boolean oneAttempt = ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-D" + attempts + "=1");
        String allMethods = "jdk.httpclient.enableAllMethodRetry";
        String allMethodsValue = System.getProperty(allMethods);
        boolean allMethodsStartup = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(arg -> (arg.equals("-D" + allMethods) || arg.startsWith("-D" + allMethods + "="))
                        && !arg.equals("-D" + allMethods + "=false"));
        if (!startup || !"true".equals(System.getProperty(name))
                || !oneAttempt || !"1".equals(System.getProperty(attempts))
                || allMethodsStartup || (allMethodsValue != null && !allMethodsValue.equals("false")))
            throw failure(Failure.INVALID_REQUEST, "JVM startup must disable HTTP connection retries");
    }

    public Response send(Request request) throws InterruptedException {
        CompletableFuture<Response> future = sendAsync(request);
        try { return future.get(); }
        catch (InterruptedException interrupted) { future.cancel(true); throw interrupted; }
        catch (ExecutionException error) {
            if (error.getCause() instanceof TransportException transport) throw transport;
            throw failure(Failure.IO, "Signed HTTP exchange failed");
        }
    }

    public synchronized CompletableFuture<Response> sendAsync(Request request) {
        long started = System.nanoTime();
        if (closed) throw failure(Failure.CLOSED, "Signed HTTP client is closed");
        requireNoRetryStartup();
        if (request == null || !origins.containsKey(request.audience)
                || !operations.get(request.audience).contains(request.operation))
            throw failure(Failure.INVALID_REQUEST, "Audience or operation is not allowed");
        if (request.body.length > limits.requestBytes()) throw failure(Failure.TOO_LARGE, "Request body limit exceeded");
        String nonce = Nonce.random(); long now = clock.instant().getEpochSecond();
        String proof = signer.sign(new WorkloadRequestProof(2, caller, request.audience, request.method,
                request.path, BodyHash.sha256(request.body), nonce, now, now + policy.workloadMaxTtlSeconds(), request.operation,
                BodyHash.userProof(request.userProof)));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origins.get(request.audience).toASCIIString() + request.path))
                .timeout(limits.totalDeadline()).header(REQUEST_PROOF, proof).header("Content-Type", "application/json")
                .header("Accept", "application/json").header("Accept-Encoding", "identity")
                .method(request.method, new SinglePublication(request.body));
        if (request.userProof != null) builder.header(USER_PROOF, request.userProof);
        if (!slots.tryAcquire()) throw failure(Failure.BUSY, "Signed HTTP concurrency limit exceeded");
        // No pooled connections. The JVM retrylimit=1 is still required: even a fresh GET can encounter a retriable EOF.
        HttpClient client;
        try {
            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(limits.connectTimeout()).executor(executor).proxy(DIRECT).build();
        } catch (RuntimeException error) { slots.release(); throw failure(Failure.IO, "Unable to create signed HTTP exchange"); }
        CompletableFuture<Response> result = new CompletableFuture<>(); active.add(result);
        CompletableFuture<HttpResponse<byte[]>> wire;
        try { wire = client.sendAsync(builder.build(), info -> subscriber(info)); }
        catch (RuntimeException error) { active.remove(result); slots.release(); client.shutdownNow(); throw failure(Failure.IO, "Unable to start signed HTTP exchange"); }
        ScheduledFuture<?> timer = deadlines.schedule(() -> {
            result.completeExceptionally(failure(Failure.DEADLINE, "Signed HTTP total deadline exceeded"));
        }, Math.max(0, limits.totalDeadline().toNanos() - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
        result.whenComplete((response, error) -> {
            timer.cancel(false); wire.cancel(true); client.shutdownNow(); active.remove(result); slots.release();
        });
        wire.whenComplete((response, error) -> {
            if (result.isDone()) return;
            if (error != null) { result.completeExceptionally(sanitize(error)); return; }
            try {
                List<String> tokens = response.headers().allValues(RESPONSE_PROOF);
                if (tokens.size() != 1) throw failure(Failure.UNTRUSTED_RESPONSE, "Missing or duplicate response proof");
                var verified = verifier.verifyResponse(tokens.getFirst(), new ExpectedResponse(request.audience, caller, nonce,
                        response.statusCode(), BodyHash.sha256(response.body())));
                result.complete(new Response(response.statusCode(), response.body(), verified));
            } catch (RuntimeException invalid) {
                result.completeExceptionally(failure(Failure.UNTRUSTED_RESPONSE, "Response proof or request binding is invalid"));
            }
        });
        return result;
    }

    private HttpResponse.BodySubscriber<byte[]> subscriber(HttpResponse.ResponseInfo info) {
        if (info.statusCode() >= 300 && info.statusCode() < 400)
            return new BoundedSubscriber(0, failure(Failure.UNTRUSTED_RESPONSE, "Redirect response refused"));
        List<String> encodings = info.headers().allValues("Content-Encoding");
        if (!encodings.isEmpty() && (encodings.size() != 1 || !encodings.getFirst().equalsIgnoreCase("identity")))
            return new BoundedSubscriber(0, failure(Failure.UNTRUSTED_RESPONSE, "Encoded response refused"));
        List<String> proofs = info.headers().allValues(RESPONSE_PROOF);
        if (proofs.size() != 1 || proofs.getFirst().length() > 12288)
            return new BoundedSubscriber(0, failure(Failure.UNTRUSTED_RESPONSE, "Missing or oversized response proof"));
        try {
            if (info.headers().firstValueAsLong("Content-Length").orElse(0) > limits.responseBytes())
                return new BoundedSubscriber(0, failure(Failure.TOO_LARGE, "Response body limit exceeded"));
        } catch (RuntimeException invalid) {
            return new BoundedSubscriber(0, failure(Failure.UNTRUSTED_RESPONSE, "Invalid response length"));
        }
        return new BoundedSubscriber(limits.responseBytes(), null);
    }

    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output;
        private final int maximum;
        private final TransportException rejection;
        private Flow.Subscription subscription;
        private BoundedSubscriber(int maximum, TransportException rejection) {
            this.maximum = maximum; this.rejection = rejection; this.output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (rejection != null) { subscription.cancel(); body.completeExceptionally(rejection); }
            else subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximum - output.size()) {
                    subscription.cancel(); body.completeExceptionally(failure(Failure.TOO_LARGE, "Response body limit exceeded")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); output.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(output.toByteArray()); }
    }

    private static final class SinglePublication implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final AtomicBoolean used = new AtomicBoolean();
        private SinglePublication(byte[] body) { delegate = HttpRequest.BodyPublishers.ofByteArray(body); }
        public long contentLength() { return delegate.contentLength(); }
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            if (used.compareAndSet(false, true)) delegate.subscribe(subscriber);
            else {
                subscriber.onSubscribe(new Flow.Subscription() { public void request(long n) { } public void cancel() { } });
                subscriber.onError(failure(Failure.IO, "Repeated request publication refused"));
            }
        }
    }

    private static TransportException sanitize(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof TransportException transport) return transport;
        if (error instanceof HttpTimeoutException) return failure(Failure.DEADLINE, "Signed HTTP total deadline exceeded");
        return failure(Failure.IO, "Signed HTTP exchange failed");
    }
    private static TransportException failure(Failure failure, String message) { return new TransportException(failure, message); }

    boolean executorsTerminated() { return executor.isTerminated() && deadlines.isTerminated(); }

    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; active.forEach(future -> future.cancel(true)); }
        deadlines.shutdownNow(); executor.shutdownNow();
        try { deadlines.awaitTermination(2, TimeUnit.SECONDS); executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
