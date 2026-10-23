package com.aetheris.auth.service;

import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded single-instance account and source failure windows; multiple instances need shared storage. */
@Service
public class LoginAttemptService {
    private static final long WINDOW_MS = Duration.ofMinutes(15).toMillis();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Clock clock;
    private final int capacity;
    public LoginAttemptService() { this(Clock.systemUTC(), 10_000); }
    LoginAttemptService(Clock clock, int capacity) { this.clock = clock; this.capacity = capacity; }
    public synchronized boolean allow(String username) { return allowed(account(username), 5); }
    public synchronized boolean allow(String username, String source) {
        expire();
        return allowed(account(username), 5) && (source == null || allowed("ip:" + source, 30));
    }
    public synchronized void recordFailure(String username) { record(account(username), 5); }
    public synchronized void recordFailure(String username, String source) {
        record(account(username), 5);
        if (source != null) record("ip:" + source, 30);
    }
    public synchronized void reset(String username) { attempts.remove(account(username)); }
    private boolean allowed(String key, int limit) {
        expire();
        Attempt attempt = attempts.get(key);
        // Never evict a locked account to make room for an attacker's new names.
        return attempt == null ? attempts.size() < capacity : attempt.failures < limit;
    }
    private void record(String key, int limit) {
        expire();
        Attempt previous = attempts.get(key);
        if (previous == null) {
            if (attempts.size() < capacity) attempts.put(key, new Attempt(1, clock.millis()));
        } else if (previous.failures < limit) {
            attempts.put(key, new Attempt(previous.failures + 1, clock.millis()));
        }
        // Once locked, rejected calls cannot extend the window indefinitely.
    }
    private void expire() {
        long oldest = clock.millis() - WINDOW_MS;
        attempts.values().removeIf(attempt -> attempt.lastFailureAt <= oldest);
    }
    private String account(String username) {
        return "user:" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }
    private record Attempt(int failures, long lastFailureAt) { }
}
