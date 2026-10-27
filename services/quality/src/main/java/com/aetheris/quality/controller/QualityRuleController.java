package com.aetheris.quality.controller;

import com.aetheris.quality.entity.QualityRule;
import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.service.QualityCheckResultService;
import com.aetheris.quality.service.QualityExecutionService;
import com.aetheris.quality.service.QualityRuleService;
import com.aetheris.quality.security.GatewayIdentityInterceptor;
import com.aetheris.quality.service.QualitySchedulerService;
import com.aetheris.security.protocol.pagination.VisibleListCursor;
import com.aetheris.security.protocol.pagination.VisibleListPage;
import com.aetheris.security.protocol.pagination.VisibleListScanner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/quality/rules")
public class QualityRuleController {
    private com.aetheris.quality.security.ResourceAuthorization authorization = new com.aetheris.quality.security.ResourceAuthorization();
    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthorization(com.aetheris.quality.security.ResourceAuthorization authorization) { this.authorization = authorization; }

    private final QualityRuleService qualityRuleService;
    private final QualityCheckResultService resultService;
    private final QualityExecutionService executionService;
    private final QualitySchedulerService schedulerService;

    public QualityRuleController(
            QualityRuleService qualityRuleService,
            QualityCheckResultService resultService,
            QualityExecutionService executionService,
            QualitySchedulerService schedulerService) {
        this.qualityRuleService = qualityRuleService;
        this.resultService = resultService;
        this.executionService = executionService;
        this.schedulerService = schedulerService;
    }

    @GetMapping
    public List<QualityRule> list() {
        // Keep the old array contract for callers that do not have a request
        // context (including standalone unit callers). An authenticated HTTP
        // request uses the bounded scanner and fails explicitly when it cannot
        // fit in the legacy response.
        if (authorization.username() != null) {
            VisibleListPage<QualityRule> page = page(200, null, null, null, null);
            if (page.hasMore()) throw compatibilityLimit();
            return page.items();
        }
        return authorization.filter("quality_rule", qualityRuleService.list(), QualityRule::getId);
    }

    /** Cursor page. Permission is checked on each bounded candidate window, never after a SQL LIMIT. */
    @GetMapping("/page")
    public VisibleListPage<QualityRule> page(
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String status) {
        var proof = authorization.currentUser().proof();
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String hash = filterHash("quality-rules", normalizedKeyword, ruleType, status, size,
                proof.uid(), proof.username(), proof.accessVersion());
        return VisibleListScanner.page(cursor, size, hash, upperRules(normalizedKeyword, ruleType, status),
                (upper, before, limit) -> qualityRuleService.list(new LambdaQueryWrapper<QualityRule>()
                        .and(!normalizedKeyword.isBlank(), q -> q.like(QualityRule::getName, normalizedKeyword)
                                .or().like(QualityRule::getTargetTable, normalizedKeyword)
                                .or().like(QualityRule::getDescription, normalizedKeyword))
                        .eq(ruleType != null && !ruleType.isBlank(), QualityRule::getRuleType, ruleType)
                        .eq(status != null && !status.isBlank(), QualityRule::getStatus, status)
                        .le(QualityRule::getId, upper)
                        .lt(before != null, QualityRule::getId, before)
                        .orderByDesc(QualityRule::getId)
                        .last("LIMIT " + limit)),
                QualityRule::getId,
                rows -> authorization.filter("quality_rule", rows, QualityRule::getId)
                        .stream().map(QualityRule::getId).collect(Collectors.toSet()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<QualityRule> getById(@PathVariable Long id) {
        authorization.require("quality_rule", id, "view");
        QualityRule rule = qualityRuleService.getById(id);
        return rule != null ? ResponseEntity.ok(rule) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public QualityRule create(@Valid @RequestBody QualityRule rule) {
        authorization.require("quality_rule", null, "create");
        authorization.requireManage();
        rule.setId(null);
        applyDefaults(rule);
        QualitySchedulerService.validateCron(rule.getCronExpression());
        qualityRuleService.save(rule);
        schedulerService.sync(rule);
        return rule;
    }

    @PutMapping("/{id}")
    public ResponseEntity<QualityRule> update(
            @PathVariable Long id,
            @Valid @RequestBody QualityRule rule) {
        authorization.require("quality_rule", id, "update");
        QualityRule existing=qualityRuleService.getById(id);
        authorization.protectDefinition(rule,existing,java.util.Set.of("name","description","status","cronExpression"));
        if(existing!=null) {
            if(!StringUtils.hasText(rule.getStatus()))rule.setStatus(existing.getStatus());
            if ((rule.getCronExpression()!=null && !java.util.Objects.equals(rule.getCronExpression(),existing.getCronExpression()))
                    || ("disabled".equals(existing.getStatus()) && "enabled".equals(rule.getStatus()))) {
                authorization.require("quality_rule",id,"execute");
            }
        }
        rule.setId(id);
        applyDefaults(rule);
        QualitySchedulerService.validateCron(rule.getCronExpression());
        authorization.retainEditable(rule,java.util.Set.of("name","description","status","cronExpression"));
        boolean updated = qualityRuleService.updateById(rule);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        QualityRule saved = qualityRuleService.getById(id);
        schedulerService.sync(saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        authorization.require("quality_rule", id, "delete");
        String unresolved = "仍有执行中或待对账检查，禁止删除质量规则";
        if (resultService.count(new LambdaQueryWrapper<QualityCheckResult>().eq(QualityCheckResult::getRuleId,id)
                .in(QualityCheckResult::getStatus,java.util.List.of("running","unknown"))) > 0) {
            return ResponseEntity.status(409).body(Map.of("message",unresolved));
        }
        boolean removed;
        try { removed = qualityRuleService.removeById(id); }
        catch (org.springframework.dao.DataAccessException failure) {
            // The database trigger is the final guard against a run starting
            // between the friendly precheck and deletion.
            for (Throwable cause=failure; cause!=null; cause=cause.getCause()) {
                if(cause instanceof java.sql.SQLException sql && "55000".equals(sql.getSQLState()))
                    return ResponseEntity.status(409).body(Map.of("message",unresolved));
            }
            throw failure;
        }
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        // 先删数据再取消调度：调度残留最多导致一次「规则不存在」的失败日志，反向则可能漏删。
        schedulerService.unschedule(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(
            @PathVariable Long id,
            @RequestAttribute("com.aetheris.security.servlet.TrustedUser.authenticated") com.aetheris.security.servlet.TrustedUser user) {
        authorization.require("quality_rule", id, "execute");
        try {
            return ResponseEntity.ok(executionService.executeManual(id, user));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(409).body(Map.of("message", error.getMessage()));
        } catch (RuntimeException error) {
            return ResponseEntity.status(502).body(Map.of("message", error.getMessage()));
        }
    }

    @GetMapping("/{id}/results")
    public List<QualityCheckResult> results(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int limit) {
        if (authorization.username() != null) {
            VisibleListPage<QualityCheckResult> page = resultPage(id, Math.clamp(limit, 1, 200), null);
            if (page.hasMore()) throw compatibilityLimit();
            return page.items();
        }
        authorization.require("quality_rule", id, "view");
        return resultService.list(new LambdaQueryWrapper<QualityCheckResult>()
                .eq(QualityCheckResult::getRuleId, id)
                .orderByDesc(QualityCheckResult::getStartedAt)
                .last("LIMIT " + Math.clamp(limit, 1, 200)));
    }

    @GetMapping("/{id}/results/page")
    public VisibleListPage<QualityCheckResult> resultPage(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(required = false) String cursor) {
        authorization.require("quality_rule", id, "view");
        var proof = authorization.currentUser().proof();
        String hash = filterHash("quality-results-rule", id, size, proof.uid(), proof.username(), proof.accessVersion());
        return VisibleListScanner.page(cursor, size, hash, upperResults(id, null),
                (upper, before, limit) -> resultService.list(new LambdaQueryWrapper<QualityCheckResult>()
                        .eq(QualityCheckResult::getRuleId, id)
                        .le(QualityCheckResult::getId, upper)
                        .lt(before != null, QualityCheckResult::getId, before)
                        .orderByDesc(QualityCheckResult::getId)
                        .last("LIMIT " + limit)),
                QualityCheckResult::getId,
                rows -> authorization.filter("quality_rule", rows, QualityCheckResult::getRuleId)
                        .stream().map(QualityCheckResult::getId).collect(Collectors.toSet()));
    }

    /** 全局检查历史（质量趋势页用），按开始时间倒序，可按规则/状态过滤。 */
    @GetMapping("/results/all")
    public List<QualityCheckResult> allResults(
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        if (authorization.username() != null) {
            VisibleListPage<QualityCheckResult> page = allResultsPage(ruleId, status, Math.clamp(limit, 1, 200), null);
            if (page.hasMore()) throw compatibilityLimit();
            return page.items();
        }
        return authorization.filter("quality_rule", resultService.list(new LambdaQueryWrapper<QualityCheckResult>()
                .eq(ruleId != null, QualityCheckResult::getRuleId, ruleId)
                .eq(StringUtils.hasText(status), QualityCheckResult::getStatus, status)
                .orderByDesc(QualityCheckResult::getStartedAt)
                .last("LIMIT " + Math.clamp(limit, 1, 200))), QualityCheckResult::getRuleId);
    }

    @GetMapping("/results/page")
    public VisibleListPage<QualityCheckResult> allResultsPage(
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor) {
        var proof = authorization.currentUser().proof();
        String hash = filterHash("quality-results", ruleId, status, size, proof.uid(), proof.username(), proof.accessVersion());
        return VisibleListScanner.page(cursor, size, hash, upperResults(ruleId, status),
                (upper, before, limit) -> resultService.list(new LambdaQueryWrapper<QualityCheckResult>()
                        .eq(ruleId != null, QualityCheckResult::getRuleId, ruleId)
                        .eq(StringUtils.hasText(status), QualityCheckResult::getStatus, status)
                        .le(QualityCheckResult::getId, upper)
                        .lt(before != null, QualityCheckResult::getId, before)
                        .orderByDesc(QualityCheckResult::getId)
                        .last("LIMIT " + limit)),
                QualityCheckResult::getId,
                rows -> authorization.filter("quality_rule", rows, QualityCheckResult::getRuleId)
                        .stream().map(QualityCheckResult::getId).collect(Collectors.toSet()));
    }

    /** 最近一次检查的质量概览：通过率、失败/异常规则数。统计基于规则的 last_check 快照。 */
    @GetMapping("/results/summary")
    public Map<String, Object> summary() {
        List<QualityRule> rules = authorization.filter("quality_rule", qualityRuleService.list(), QualityRule::getId);
        long enabled = rules.stream().filter(rule -> "enabled".equals(rule.getStatus())).count();
        long passed = rules.stream().filter(rule -> "passed".equals(rule.getLastCheckStatus())).count();
        long failed = rules.stream().filter(rule -> "failed".equals(rule.getLastCheckStatus())).count();
        long errored = rules.stream().filter(rule -> "error".equals(rule.getLastCheckStatus())).count();
        long checked = passed + failed + errored;
        // 质量分 = 通过规则数 / 已检查规则数；从未检查过时给满分是误导，给 null 让前端显示「暂无数据」。
        Double score = checked == 0 ? null : Math.round(passed * 1000.0 / checked) / 10.0;
        return Map.of(
                "enabledRules", enabled,
                "passed", passed,
                "failed", failed,
                "errored", errored,
                "checked", checked,
                "score", score == null ? -1 : score);
    }

    private void applyDefaults(QualityRule rule) {
        if (rule.getThreshold() == null) rule.setThreshold(0);
        if (rule.getSeverity() == null || rule.getSeverity().isBlank()) rule.setSeverity("medium");
        if (rule.getStatus() == null || rule.getStatus().isBlank()) rule.setStatus("enabled");
    }

    private long upperRules(String keyword, String ruleType, String status) {
        return qualityRuleService.list(new LambdaQueryWrapper<QualityRule>()
                .and(keyword != null && !keyword.isBlank(), q -> q.like(QualityRule::getName, keyword)
                        .or().like(QualityRule::getTargetTable, keyword)
                        .or().like(QualityRule::getDescription, keyword))
                .eq(ruleType != null && !ruleType.isBlank(), QualityRule::getRuleType, ruleType)
                .eq(status != null && !status.isBlank(), QualityRule::getStatus, status)
                .select(QualityRule::getId).orderByDesc(QualityRule::getId).last("LIMIT 1"))
                .stream().map(QualityRule::getId).filter(java.util.Objects::nonNull).findFirst().orElse(0L);
    }

    private long upperResults(Long ruleId, String status) {
        return resultService.list(new LambdaQueryWrapper<QualityCheckResult>()
                .select(QualityCheckResult::getId)
                .eq(ruleId != null, QualityCheckResult::getRuleId, ruleId)
                .eq(StringUtils.hasText(status), QualityCheckResult::getStatus, status)
                .orderByDesc(QualityCheckResult::getId).last("LIMIT 1"))
                .stream().map(QualityCheckResult::getId).filter(java.util.Objects::nonNull).findFirst().orElse(0L);
    }

    private static String filterHash(Object... parts) {
        String canonical = Arrays.stream(parts).map(value -> {
            String text = value == null ? "<null>" : String.valueOf(value);
            return text.length() + ":" + text;
        }).collect(Collectors.joining("|"));
        return VisibleListCursor.hash(canonical);
    }

    private static org.springframework.web.server.ResponseStatusException compatibilityLimit() {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "列表超过兼容数组上限，请使用分页接口");
    }
}
