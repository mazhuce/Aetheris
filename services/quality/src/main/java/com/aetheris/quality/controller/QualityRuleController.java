package com.aetheris.quality.controller;

import com.aetheris.quality.entity.QualityRule;
import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.service.QualityCheckResultService;
import com.aetheris.quality.service.QualityExecutionService;
import com.aetheris.quality.service.QualityRuleService;
import com.aetheris.quality.service.QualitySchedulerService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quality/rules")
public class QualityRuleController {

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
        return qualityRuleService.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<QualityRule> getById(@PathVariable Long id) {
        QualityRule rule = qualityRuleService.getById(id);
        return rule != null ? ResponseEntity.ok(rule) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public QualityRule create(@Valid @RequestBody QualityRule rule) {
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
        rule.setId(id);
        applyDefaults(rule);
        QualitySchedulerService.validateCron(rule.getCronExpression());
        boolean updated = qualityRuleService.updateById(rule);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        QualityRule saved = qualityRuleService.getById(id);
        schedulerService.sync(saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = qualityRuleService.removeById(id);
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
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username) {
        try {
            return ResponseEntity.ok(executionService.execute(id, username));
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
        return resultService.list(new LambdaQueryWrapper<QualityCheckResult>()
                .eq(QualityCheckResult::getRuleId, id)
                .orderByDesc(QualityCheckResult::getStartedAt)
                .last("LIMIT " + Math.clamp(limit, 1, 200)));
    }

    /** 全局检查历史（质量趋势页用），按开始时间倒序，可按规则/状态过滤。 */
    @GetMapping("/results/all")
    public List<QualityCheckResult> allResults(
            @RequestParam(required = false) Long ruleId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return resultService.list(new LambdaQueryWrapper<QualityCheckResult>()
                .eq(ruleId != null, QualityCheckResult::getRuleId, ruleId)
                .eq(StringUtils.hasText(status), QualityCheckResult::getStatus, status)
                .orderByDesc(QualityCheckResult::getStartedAt)
                .last("LIMIT " + Math.clamp(limit, 1, 200)));
    }

    /** 最近一次检查的质量概览：通过率、失败/异常规则数。统计基于规则的 last_check 快照。 */
    @GetMapping("/results/summary")
    public Map<String, Object> summary() {
        List<QualityRule> rules = qualityRuleService.list();
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
}
