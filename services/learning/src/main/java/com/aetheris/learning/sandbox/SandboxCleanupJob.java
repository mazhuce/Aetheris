package com.aetheris.learning.sandbox;

import com.aetheris.learning.config.LearningProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时回收过期 Light 沙箱，单次最多 50 个，避免长事务占用连接池。 */
@Component
public class SandboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SandboxCleanupJob.class);
    private final SandboxService sandboxService;
    private final LearningProperties properties;

    public SandboxCleanupJob(SandboxService sandboxService, LearningProperties properties) {
        this.sandboxService = sandboxService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${aetheris.learning.sandbox.cleanup-interval-ms:600000}",
            initialDelayString = "${aetheris.learning.sandbox.cleanup-interval-ms:600000}")
    public void recycle() {
        int limit = Math.max(1, properties.getSandbox().getMaxActivePerUser() * 10);
        try {
            int recycled = sandboxService.recycleExpired(Math.min(limit, 50));
            if (recycled > 0) log.info("已回收 {} 个过期学习沙箱", recycled);
        } catch (RuntimeException failure) {
            log.warn("沙箱回收任务失败: {}", failure.getMessage());
        }
    }
}
