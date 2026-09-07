package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.ConnectionHealth;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.DataSourceHealthObservation;
import com.aetheris.metadata.mapper.DataSourceHealthMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DataSourceHealthService {
    private final DataSourceHealthMapper mapper;
    private final JdbcService jdbc;
    private final Clock clock;
    private final long ttlSeconds;
    private final long timeoutMs;
    private final ThreadPoolExecutor workers;

    @Autowired
    public DataSourceHealthService(DataSourceHealthMapper mapper, JdbcService jdbc,
                                  @Value("${aetheris.datasource.health.ttl-seconds:60}") long ttlSeconds,
                                  @Value("${aetheris.datasource.health.timeout-ms:10000}") long timeoutMs,
                                  @Value("${aetheris.datasource.health.max-concurrent:4}") int maxConcurrent) {
        this(mapper, jdbc, Clock.systemUTC(), ttlSeconds, timeoutMs, maxConcurrent);
    }

    DataSourceHealthService(DataSourceHealthMapper mapper, JdbcService jdbc, Clock clock,
                            long ttlSeconds, long timeoutMs, int maxConcurrent) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.clock = clock;
        this.ttlSeconds = Math.max(1, Math.min(ttlSeconds, 300));
        this.timeoutMs = Math.max(1, Math.min(timeoutMs, 30000));
        // No queue: even a driver ignoring interrupts can occupy only a bounded number of workers.
        this.workers = new ThreadPoolExecutor(0, Math.max(1, Math.min(maxConcurrent, 16)), 60,
                TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "datasource-connection-probe");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public List<DataSource> attach(List<DataSource> sources) {
        if (sources.isEmpty()) return sources;
        Map<Long, DataSourceHealthObservation> observations = mapper.findAll(
                        sources.stream().map(DataSource::getId).toList()).stream()
                .collect(Collectors.toMap(DataSourceHealthObservation::datasourceId, Function.identity()));
        sources.forEach(source -> source.setConnectionHealth(effective(source, observations.get(source.getId()))));
        return sources;
    }

    public DataSource attach(DataSource source) {
        if (source != null) source.setConnectionHealth(effective(source, mapper.find(source.getId())));
        return source;
    }

    ConnectionHealth effective(DataSource source, DataSourceHealthObservation observation) {
        if (observation == null || observation.configurationRevision() != revision(source))
            return ConnectionHealth.unknown();
        String status = observation.status();
        if (observation.expiresAt() == null || !now().isBefore(observation.expiresAt())) status = "unknown";
        return new ConnectionHealth(status, observation.checkedAt(), observation.lastSuccessAt(),
                observation.expiresAt(), observation.latencyMs(), observation.failureCode(), observation.failureMessage());
    }

    /** Unsaved tests use the same timeout/concurrency/redaction, but never create persisted observations. */
    public Map<String, Object> testBeforeSave(DataSource source) {
        ProbeResult result = probe(source);
        Map<String, Object> response = result.response();
        OffsetDateTime checkedAt = now();
        response.put("connectionHealth", new ConnectionHealth(result.status(), checkedAt,
                result.success() ? checkedAt : null, checkedAt.plusSeconds(ttlSeconds), result.latencyMs(),
                result.failure() == null ? null : result.failure().code(),
                result.failure() == null ? null : result.failure().message()));
        return response;
    }

    public Map<String, Object> test(DataSource source) {
        String token = UUID.randomUUID().toString();
        OffsetDateTime startedAt = now();
        long revision = revision(source);
        if (mapper.claim(source.getId(), revision, token, startedAt,
                startedAt.plusNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMs + 1000))) != 1) {
            return responseWithoutObservation(source, "PROBE_BUSY", "已有连接探测正在进行，或配置已更新，请稍后重试");
        }
        ProbeResult result = probe(source);
        OffsetDateTime checkedAt = now();
        if (mapper.complete(source.getId(), revision, token, result.status(), checkedAt,
                checkedAt.plusSeconds(ttlSeconds), result.latencyMs(),
                result.failure() == null ? null : result.failure().code(),
                result.failure() == null ? null : result.failure().message()) != 1) {
            return responseWithoutObservation(source, "PROBE_SUPERSEDED", "配置或探测已更新，本次结果已作废，请重新探测");
        }
        Map<String, Object> response = result.response();
        response.put("connectionHealth", effective(source, mapper.find(source.getId())));
        return response;
    }

    private Map<String, Object> responseWithoutObservation(DataSource source, String code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        result.put("failureCode", code);
        result.put("connectionHealth", effective(source, mapper.find(source.getId())));
        return result;
    }

    private ProbeResult probe(DataSource source) {
        long startedAt = System.nanoTime();
        Future<Map<String, Object>> future;
        try {
            future = workers.submit(() -> jdbc.probe(source, Math.max(1, (int) ((timeoutMs + 999) / 1000))));
        } catch (RejectedExecutionException error) {
            return new ProbeResult(false, "unknown", elapsed(startedAt), Map.of(),
                    new ConnectionProbeFailure("PROBE_BUSY", "连接探测并发已达上限，请稍后重试"));
        }
        try {
            Map<String, Object> metadata = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return new ProbeResult(true, "available", elapsed(startedAt), metadata, null);
        } catch (TimeoutException error) {
            future.cancel(true);
            return new ProbeResult(false, "unavailable", elapsed(startedAt), Map.of(), ConnectionProbeFailure.timeout());
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "unknown", elapsed(startedAt), Map.of(),
                    new ConnectionProbeFailure("PROBE_INTERRUPTED", "连接探测已中断，请稍后重试"));
        } catch (ExecutionException error) {
            return new ProbeResult(false, "unavailable", elapsed(startedAt), Map.of(), ConnectionProbeFailure.from(error.getCause()));
        }
    }

    private static long revision(DataSource source) { return source.getConnectionRevision() == null ? 0 : source.getConnectionRevision(); }
    private static long elapsed(long startedAt) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt); }
    private OffsetDateTime now() { return OffsetDateTime.now(clock); }

    @PreDestroy
    public void close() { workers.shutdownNow(); }

    private record ProbeResult(boolean success, String status, long latencyMs,
                               Map<String, Object> metadata, ConnectionProbeFailure failure) {
        Map<String, Object> response() {
            Map<String, Object> result = new LinkedHashMap<>(metadata);
            result.put("success", success);
            result.put("message", success ? "连接成功（SELECT 1）" : failure.message());
            result.put("latencyMs", latencyMs);
            if (failure != null) result.put("failureCode", failure.code());
            return result;
        }
    }
}
