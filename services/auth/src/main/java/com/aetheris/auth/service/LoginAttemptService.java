package com.aetheris.auth.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按用户名限制登录失败次数，缓解在线口令爆破。
 * 单机内存实现，重启后计数清零；多实例部署时需替换为共享存储。
 */
@Service
public class LoginAttemptService {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 是否允许本次尝试；锁定期满后自动放行。 */
    public boolean allow(String username) {
        Attempt attempt = attempts.get(key(username));
        if (attempt == null || attempt.failures.get() < MAX_FAILURES) {
            return true;
        }
        if (System.currentTimeMillis() - attempt.lastFailureAt >= LOCK_DURATION.toMillis()) {
            attempts.remove(key(username));
            return true;
        }
        return false;
    }

    public void recordFailure(String username) {
        Attempt attempt = attempts.computeIfAbsent(key(username), ignored -> new Attempt());
        attempt.failures.incrementAndGet();
        attempt.lastFailureAt = System.currentTimeMillis();
    }

    public void reset(String username) {
        attempts.remove(key(username));
    }

    private String key(String username) {
        return username == null ? "" : username.toLowerCase();
    }

    private static final class Attempt {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long lastFailureAt = System.currentTimeMillis();
    }
}
