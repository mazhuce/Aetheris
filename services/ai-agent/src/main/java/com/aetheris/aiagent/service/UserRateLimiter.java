package com.aetheris.aiagent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按用户的固定窗口限流。
 *
 * <p>相比原先直接放在 controller 里的实现，这里修掉了内存泄漏：原实现用
 * {@code ConcurrentHashMap} 累积每个用户的窗口且从不清理，用户数增长会持续占用内存。
 * 现在每次获取许可时顺带淘汰过期条目，并对表容量设上限。
 */
@Component
public class UserRateLimiter {

    /** 超过该容量时强制清理一次；防御异常流量下的无界增长。 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequestsPerWindow;
    private final long windowMillis;

    public UserRateLimiter(
            @Value("${aetheris.ai.rate-limit.max-requests:20}") int maxRequestsPerWindow,
            @Value("${aetheris.ai.rate-limit.window:PT1M}") Duration window) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowMillis = window.toMillis();
    }

    /**
     * 尝试为指定用户获取一个许可。
     *
     * @return true 表示放行，false 表示已超出配额
     */
    public boolean tryAcquire(String username) {
        long now = System.currentTimeMillis();
        if (windows.size() > CLEANUP_THRESHOLD) {
            evictExpired(now);
        }

        // compute 对同一 key 的操作是原子的，避免并发下多个请求各自新建窗口而放大配额。
        Window window = windows.compute(username, (ignored, current) -> {
            if (current == null || now - current.startedAt >= windowMillis) {
                return new Window(now);
            }
            return current;
        });
        return window.counter.incrementAndGet() <= maxRequestsPerWindow;
    }

    /** 淘汰已过期的窗口；过期条目不再影响配额，留着只是占内存。 */
    private void evictExpired(long now) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().startedAt >= windowMillis) {
                iterator.remove();
            }
        }
    }

    private static final class Window {
        private final long startedAt;
        private final AtomicInteger counter = new AtomicInteger();

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
