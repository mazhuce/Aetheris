package com.aetheris.serving.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ServingRateLimiter {
    private final int capacity; private final long windowMs; private final Clock clock = Clock.systemUTC(); private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    public ServingRateLimiter(@Value("${aetheris.rate-limit.capacity:60}") int capacity,@Value("${aetheris.rate-limit.window-ms:60000}") long windowMs){if(capacity<1||capacity>10000)throw new IllegalArgumentException("限流容量必须在 1 到 10000 之间");if(windowMs<1000||windowMs>3600000)throw new IllegalArgumentException("限流窗口必须在 1 秒到 1 小时之间");this.capacity=capacity;this.windowMs=windowMs;}
    public boolean allow(String subject,String route){String key=subject+"|"+route;long now=clock.millis();Window current=windows.compute(key,(ignored,old)->old==null||now-old.startedAt()>=windowMs?new Window(now,new AtomicInteger(1)):new Window(old.startedAt(),new AtomicInteger(old.count().incrementAndGet())));return current.count().get()<=capacity;}
    private record Window(long startedAt,AtomicInteger count){}
}
