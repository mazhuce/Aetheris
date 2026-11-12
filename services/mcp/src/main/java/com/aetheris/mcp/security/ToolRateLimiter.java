package com.aetheris.mcp.security;

import com.aetheris.mcp.config.McpProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单实例固定窗口限流。M2 只保护 tools/call，避免一次对话把数据库打满；
 * 多副本部署时需要替换为 Redis 计数器并纳入平台限流体系。
 */
@Component
public class ToolRateLimiter {

    private final McpProperties properties;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    public ToolRateLimiter(McpProperties properties) {
        this.properties = properties;
    }

    private record Window(long epochMinute, AtomicInteger count) {
    }

    public boolean tryAcquire(long uid) {
        long minute = Instant.now().getEpochSecond() / 60;
        Window window = windows.compute(uid, (key, existing) ->
                existing == null || existing.epochMinute() != minute
                        ? new Window(minute, new AtomicInteger())
                        : existing);
        return window.count().incrementAndGet() <= Math.max(1, properties.getRateLimitCapacity());
    }

    public int remaining(long uid) {
        long minute = Instant.now().getEpochSecond() / 60;
        Window window = windows.get(uid);
        if (window == null || window.epochMinute() != minute) return properties.getRateLimitCapacity();
        return Math.max(0, properties.getRateLimitCapacity() - window.count().get());
    }
}
