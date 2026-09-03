package com.aetheris.system.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 服务监控：聚合探测各微服务的健康状态，返回统一列表。
 *
 * <p>HTTP 服务走 /actuator/health；postgres 为纯 TCP 服务，用 socket 握手探测。
 * 探测并发执行、单服务 3 秒超时，单个服务不可达不影响其他服务的探测结果。
 * 服务地址与 docker-compose 中的服务名/端口一一对应，新增服务时在 TARGETS 追加即可。
 */
@RestController
@RequestMapping("/api/system/service-health")
public class ServiceHealthController {

    private sealed interface Target permits HttpTarget, TcpTarget {
        String name();
    }

    private record HttpTarget(String name, String url) implements Target {
    }

    private record TcpTarget(String name, String host, int port) implements Target {
    }

    private static final List<Target> TARGETS = List.of(
            new HttpTarget("gateway 网关", "http://gateway:8080/actuator/health"),
            new HttpTarget("auth 认证", "http://auth-service:8087/actuator/health"),
            new HttpTarget("metadata 元数据", "http://metadata-service:8082/actuator/health"),
            new HttpTarget("quality 质量", "http://quality-service:8083/actuator/health"),
            new HttpTarget("ingestion 接入", "http://ingestion-service:8084/actuator/health"),
            new HttpTarget("governance 治理", "http://governance-service:8085/actuator/health"),
            new HttpTarget("ai-agent 智能服务", "http://ai-agent:8086/actuator/health"),
            new HttpTarget("workflow 任务调度", "http://workflow-service:8088/actuator/health"),
            new HttpTarget("serving 数据服务", "http://serving-service:8089/actuator/health"),
            new HttpTarget("web 前端", "http://web:80/healthz"),
            new TcpTarget("postgres 数据库", "postgres", 5432));

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public record ServiceStatus(String name, String status, String detail, String checkedAt) {
    }

    @GetMapping
    public ResponseEntity<List<ServiceStatus>> health() {
        // 收集器负责合并并行分区，避免多个线程同时写普通 ArrayList。
        List<ServiceStatus> results = TARGETS.parallelStream()
                .map(this::probe)
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
        return ResponseEntity.ok(results);
    }

    private ServiceStatus probe(Target target) {
        return switch (target) {
            case HttpTarget http -> probeHttp(http);
            case TcpTarget tcp -> probeTcp(tcp);
        };
    }

    private ServiceStatus probeHttp(HttpTarget target) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target.url()))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // 2xx-4xx 都说明服务本身在线（4xx 是路由/认证问题，不是宕机）
            boolean up = response.statusCode() < 500;
            String detail = response.statusCode() + " " + response.body().replaceAll("\\s+", " ");
            if (detail.length() > 120) {
                detail = detail.substring(0, 120) + "…";
            }
            return status(target.name(), up ? "UP" : "DOWN", detail);
        } catch (Exception error) {
            return status(target.name(), "DOWN", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private ServiceStatus probeTcp(TcpTarget target) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), 3000);
            return status(target.name(), "UP", "TCP " + target.host() + ":" + target.port() + " 可达");
        } catch (Exception error) {
            return status(target.name(), "DOWN", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private ServiceStatus status(String name, String status, String detail) {
        return new ServiceStatus(name, status, detail, OffsetDateTime.now().toString());
    }
}
