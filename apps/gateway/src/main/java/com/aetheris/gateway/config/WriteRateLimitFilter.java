package com.aetheris.gateway.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class WriteRateLimitFilter implements GlobalFilter, Ordered {

    private final int capacity;
    private final long windowMillis;
    private final Cache<String, Window> windows;
    private final ReactiveStringRedisTemplate redis;
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); "
                    + "if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end; return n",
            Long.class);

    @Autowired
    public WriteRateLimitFilter(
            @Value("${aetheris.rate-limit.capacity:60}") int capacity,
            @Value("${aetheris.rate-limit.window-seconds:60}") long windowSeconds,
            ObjectProvider<ReactiveStringRedisTemplate> redis) {
        this(capacity, windowSeconds, redis.getIfAvailable());
    }

    private WriteRateLimitFilter(int capacity, long windowSeconds,
                                 ReactiveStringRedisTemplate redis) {
        if (capacity < 1 || windowSeconds < 1) {
            throw new IllegalStateException("Rate limit capacity and window must be positive");
        }
        this.capacity = capacity;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1000L);
        this.redis = redis;
        this.windows = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofMillis(windowMillis * 2))
                .build();
    }

    public WriteRateLimitFilter() {
        this(60, 60, (ReactiveStringRedisTemplate) null);
    }

    WriteRateLimitFilter(int capacity, long windowSeconds) {
        this(capacity, windowSeconds, (ReactiveStringRedisTemplate) null);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getURI().getPath();
        if (!isWrite(method, path)) {
            return chain.filter(exchange);
        }

        String username = exchange.getAttribute(JwtAuthFilter.AUTHENTICATED_USER_ATTRIBUTE);
        String clientIp = exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getHostString();
        String key = (username == null ? "anonymous" : username) + "@" + clientIp;
        long now = System.currentTimeMillis();
        if (redis != null) {
            long bucket = now / windowMillis;
            String redisKey = "aetheris:rate-limit:" + bucket + ":" + key;
            return redis.execute(INCREMENT, java.util.List.of(redisKey),
                            java.util.List.of(String.valueOf(windowMillis * 2)))
                    .next()
                    .switchIfEmpty(Mono.error(new IllegalStateException("Redis rate limit returned no value")))
                    .flatMap(count -> respond(exchange, chain, count, now))
                    // Rate limiting is a security boundary; do not silently fall
                    // back to a per-instance counter when shared state is down.
                    .onErrorResume(error -> {
                        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                        return exchange.getResponse().setComplete();
                    });
        }
        AtomicReference<Window> accepted = new AtomicReference<>();
        windows.asMap().compute(key, (ignored, current) -> {
            Window window = current;
            if (window == null || now - window.startedAt >= windowMillis) {
                window = new Window(now, 0);
            }
            if (window.count >= capacity) {
                accepted.set(null);
                return window;
            }
            window = new Window(window.startedAt, window.count + 1);
            accepted.set(window);
            return window;
        });

        Window window = windows.getIfPresent(key);
        if (accepted.get() == null) {
            long retryAfter = Math.max(1, (window == null ? windowMillis : windowMillis - (now - window.startedAt) + 999) / 1000);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(capacity));
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", "0");
            return exchange.getResponse().setComplete();
        }
        exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(capacity));
        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(Math.max(0, capacity - accepted.get().count)));
        return chain.filter(exchange);
    }

    private Mono<Void> respond(ServerWebExchange exchange, GatewayFilterChain chain,
                               long count, long now) {
        exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(capacity));
        if (count > capacity) {
            long retryAfter = Math.max(1, (windowMillis - (now % windowMillis) + 999) / 1000);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", "0");
            return exchange.getResponse().setComplete();
        }
        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining",
                String.valueOf(Math.max(0, capacity - count)));
        return chain.filter(exchange);
    }

    private boolean isWrite(HttpMethod method, String path) {
        if (method == null || !(method == HttpMethod.POST || method == HttpMethod.PUT
                || method == HttpMethod.PATCH || method == HttpMethod.DELETE)) {
            return false;
        }
        return path.startsWith("/api/") && !path.startsWith("/api/auth/login")
                && !path.startsWith("/api/auth/logout");
    }

    @Override
    public int getOrder() {
        return -90;
    }

    private record Window(long startedAt, int count) {
    }
}
