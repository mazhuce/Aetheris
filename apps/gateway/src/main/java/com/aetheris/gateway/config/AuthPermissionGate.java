package com.aetheris.gateway.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;

/**
 * 通过 auth-service 内部端点执行的粗粒度入口判定。
 *
 * <p>携带 X-Internal-Token 调用 /internal/permissions/may-mutate 与
 * /internal/permissions/may-manage；auth 不可用或响应异常时以 Mono.error
 * 上抛，由过滤器 fail-closed（503）处理 —— 粗粒度入口绝不能在权限服务
 * 不可用时放行写请求。
 */
@Component
public class AuthPermissionGate implements PermissionGate {

    private final WebClient client;
    private final String internalToken;

    public AuthPermissionGate(
            WebClient.Builder builder,
            @Value("${aetheris.internal-token:}") String internalToken,
            @Value("${aetheris.auth.base-url:http://localhost:8087}") String authBaseUrl) {
        this.internalToken = internalToken;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofMillis(1500));
        this.client = builder
                .baseUrl(authBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public Mono<Boolean> mayMutate(String username, String service) {
        return post("/internal/permissions/may-mutate",
                Map.of("username", username, "service", service));
    }

    @Override
    public Mono<Boolean> mayManage(String username) {
        return post("/internal/permissions/may-manage", Map.of("username", username));
    }

    private Mono<Boolean> post(String path, Map<String, String> body) {
        return client.post()
                .uri(path)
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), response ->
                        Mono.error(new IllegalStateException(
                                "auth 内部端点拒绝调用：" + response.statusCode())))
                .bodyToMono(PermissionCheckResponse.class)
                .map(PermissionCheckResponse::allowed);
    }

    /** 与 auth-service PermissionCheckResponse 对齐的最小视图。 */
    public record PermissionCheckResponse(boolean allowed, String reason) {
    }
}