package com.aetheris.learning.sandbox;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Light/Medium 沙箱：共享数据库实例，按学员隔离 schema。
 *
 * <p>每个学员一个 {@code sb_u<uid>} schema，表由教学数据集复制而来；启动实验只补齐缺失表，
 * 不覆盖学员已有结果。过期由定时任务回收，避免测试期数据无限增长。</p>
 *
 * <p>Medium 档（L6 生产工程类任务）增加并发槽位：全平台同时最多
 * {@code aetheris.learning.sandbox.medium-concurrency} 个学员占用，本人复用自己的沙箱不占新槽。
 * 槽位申请在进程内互斥，单实例下超卖为 0；多实例部署需要升级为数据库级槽位（M5 事项）。</p>
 */
@Service
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);
    /** 与 teach schema 的教学表保持一致；新增教学表时必须同步这里，否则任务会因缺表无法判分。 */
    private static final List<String> TEMPLATE_TABLES = List.of(
            "orders_raw", "order_status_dict", "shop_dim", "users", "payments", "refunds",
            "metric_dict", "daily_arrival", "table_asset", "lineage_edge",
            "job_definition", "acceptance_gate", "task_run_log", "audit_log",
            "daily_shop_gmv", "online_shoppers");

    private final LearningRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final LearningProperties properties;
    private final Object mediumSlotLock = new Object();

    public SandboxService(LearningRepository repository, JdbcTemplate jdbcTemplate, LearningProperties properties) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** 便捷入口：按任务定义的沙箱档位启动。 */
    public LearningModels.SandboxInfo ensure(long uid, String username, String labCode) {
        return ensure(uid, username, labCode, tierOf(labCode));
    }

    /** 便捷入口：按任务定义的沙箱档位重置。 */
    public LearningModels.SandboxInfo reset(long uid, String username, String labCode) {
        return reset(uid, username, labCode, tierOf(labCode));
    }

    private String tierOf(String labCode) {
        return repository.findLab(labCode).map(LearningModels.LabRecord::sandboxTier).orElse("light");
    }

    public LearningModels.SandboxInfo ensure(long uid, String username, String labCode, String tier) {        String effectiveTier = "medium".equals(tier) ? "medium" : "light";
        Instant now = Instant.now();
        synchronized (mediumSlotLock) {
            Optional<LearningModels.SandboxRow> active = repository.findActiveSandbox(uid);
            if (active.isPresent() && active.get().expiresAt().isAfter(now)) {
                LearningModels.SandboxRow row = active.get();
                boolean tierUpgrade = "medium".equals(effectiveTier) && !"medium".equals(row.tier());
                if (tierUpgrade) {
                    requireMediumSlot(uid, row);
                }
                createSchema(row.schemaName());
                createMissingTables(row.schemaName());
                if (!labCode.equals(row.labCode()) || tierUpgrade) {
                    Instant extended = now.plus(properties.getSandbox().getTtlHours(), ChronoUnit.HOURS);
                    repository.updateSandboxLab(row.id(), labCode, effectiveTier, extended);
                    return repository.findActiveSandbox(uid).map(LearningModels.SandboxRow::toInfo)
                            .orElseThrow(() -> new IllegalStateException("沙箱状态更新失败"));
                }
                return row.toInfo();
            }
            if (active.isPresent()) {
                dropSchema(active.get().schemaName());
                repository.markSandboxDropped(active.get().id());
                repository.insertLearningEvent(uid, "sandbox_expired_recovered", null, labCode, null);
            }
            if ("medium".equals(effectiveTier)) {
                requireMediumSlot(uid, null);
            }
            String schema = schemaOf(uid);
            createSchema(schema);
            createMissingTables(schema);
            Instant expiresAt = now.plus(properties.getSandbox().getTtlHours(), ChronoUnit.HOURS);
            repository.insertSandbox(uid, username, labCode, schema, effectiveTier, expiresAt);
            return repository.findActiveSandbox(uid).map(LearningModels.SandboxRow::toInfo)
                    .orElseThrow(() -> new IllegalStateException("沙箱创建后未能读取"));
        }
    }

    private void requireMediumSlot(long uid, LearningModels.SandboxRow current) {
        int occupied = repository.countActiveMediumSandboxes(uid);
        int capacity = properties.getSandbox().getMediumConcurrency();
        if (occupied >= capacity) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Medium 沙箱并发已满（" + occupied + "/" + capacity
                            + "），请稍后重试；已完成实验的学员可点击“重置沙箱”释放槽位。");
        }
    }

    /** 重置只影响调用者自己的 schema，同时释放 Medium 槽位。 */
    public LearningModels.SandboxInfo reset(long uid, String username, String labCode, String tier) {
        String effectiveTier = "medium".equals(tier) ? "medium" : "light";
        // 删除与重建必须作为一个整体，否则并发重置会互相删掉刚建好的 schema。
        synchronized (mediumSlotLock) {
            String schema = schemaOf(uid);
            repository.findActiveSandbox(uid).ifPresent(row -> repository.markSandboxDropped(row.id()));
            dropSchema(schema);
            LearningModels.SandboxInfo info = ensure(uid, username, labCode, effectiveTier);
            repository.insertLearningEvent(uid, "lab_reset", null, labCode, null);
            return info;
        }
    }

    public int recycleExpired(int limit) {
        int recycled = 0;
        for (LearningModels.SandboxRow ref : repository.listExpiredSandboxes(limit)) {
            try {
                dropSchema(ref.schemaName());
                repository.markSandboxDropped(ref.id());
                recycled++;
            } catch (RuntimeException failure) {
                log.warn("回收沙箱失败 schema={} reason={}", ref.schemaName(), failure.getMessage());
            }
        }
        return recycled;
    }

    public static String schemaOf(long uid) {
        if (uid <= 0) throw new IllegalArgumentException("非法学员标识");
        return "sb_u" + uid;
    }

    private void createSchema(String schema) {
        guard(schema);
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
    }

    private void createMissingTables(String schema) {
        guard(schema);
        for (String table : TEMPLATE_TABLES) {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + schema + "." + table
                    + " AS TABLE teach." + table);
        }
        // 学员 SQL 以最小权限角色执行：只授予本 schema，默认权限覆盖后续重置出的表。
        String role = properties.getSandbox().getDbRole();
        if (role == null || !role.matches("^[a-z_][a-z0-9_]{0,62}$")) {
            throw new IllegalStateException("沙箱执行角色配置非法");
        }
        jdbcTemplate.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
        jdbcTemplate.execute("GRANT ALL ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
        jdbcTemplate.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + schema + " GRANT ALL ON TABLES TO " + role);
    }

    private void dropSchema(String schema) {
        if (schema == null || !schema.matches("^sb_u[0-9]{1,18}$")) return;
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private static void guard(String schema) {
        if (schema == null || !schema.matches("^sb_u[0-9]{1,18}$")) {
            throw new IllegalStateException("非法沙箱 schema");
        }
    }
}
