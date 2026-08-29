package com.aetheris.aiagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRateLimiterTest {

    @Test
    @DisplayName("窗口内放行到配额上限，超出则拒绝")
    void allowsUpToLimitWithinWindow() {
        UserRateLimiter limiter = new UserRateLimiter(3, Duration.ofMinutes(1));

        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
        assertFalse(limiter.tryAcquire("alice"));
    }

    @Test
    @DisplayName("配额按用户独立计量，一个用户耗尽不影响其他人")
    void countsPerUser() {
        UserRateLimiter limiter = new UserRateLimiter(2, Duration.ofMinutes(1));

        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("alice"));
        assertFalse(limiter.tryAcquire("alice"));

        // bob 不应受 alice 的用量影响 —— 这是「按用户计量」的核心保证。
        assertTrue(limiter.tryAcquire("bob"));
        assertTrue(limiter.tryAcquire("bob"));
        assertFalse(limiter.tryAcquire("bob"));
    }

    @Test
    @DisplayName("窗口过期后配额重置")
    void resetsAfterWindowExpires() throws Exception {
        UserRateLimiter limiter = new UserRateLimiter(1, Duration.ofMillis(60));

        assertTrue(limiter.tryAcquire("alice"));
        assertFalse(limiter.tryAcquire("alice"));

        Thread.sleep(90);

        assertTrue(limiter.tryAcquire("alice"));
    }

    @Test
    @DisplayName("并发请求不会突破配额")
    void doesNotExceedLimitUnderConcurrency() throws Exception {
        int limit = 10;
        int threads = 40;
        UserRateLimiter limiter = new UserRateLimiter(limit, Duration.ofMinutes(1));
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire("alice")) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        // 原实现用 CAS 重置窗口，并发下可能多个线程各自新建窗口而放大配额；
        // 现在用 compute 保证同一 key 的窗口创建是原子的。
        assertTrue(allowed.get() <= limit, "放行数 " + allowed.get() + " 超过配额 " + limit);
    }
}
