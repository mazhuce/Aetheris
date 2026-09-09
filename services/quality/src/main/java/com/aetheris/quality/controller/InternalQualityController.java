package com.aetheris.quality.controller;

import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.service.QualityExecutionService;
import com.aetheris.quality.service.QualityCheckResultService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-only entry point used by workflow quality nodes. */
@RestController
@RequestMapping("/internal/quality/rules")
public class InternalQualityController {
    private final QualityExecutionService executionService;
    private final QualityCheckResultService resultService;

    public InternalQualityController(QualityExecutionService executionService,
                                     QualityCheckResultService resultService) {
        this.executionService = executionService;
        this.resultService = resultService;
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}/latest")
    public ResponseEntity<QualityCheckResult> latest(@PathVariable Long id) {
        QualityCheckResult result = resultService.getOne(new LambdaQueryWrapper<QualityCheckResult>()
                .eq(QualityCheckResult::getRuleId, id)
                .orderByDesc(QualityCheckResult::getFinishedAt).last("LIMIT 1"));
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<QualityCheckResult> run(@PathVariable Long id,
                                                  @RequestBody(required = false) RunRequest request) {
        String actor = request != null && StringUtils.hasText(request.triggeredBy())
                ? request.triggeredBy() : "workflow";
        return ResponseEntity.ok(executionService.execute(id, actor));
    }

    public record RunRequest(String triggeredBy) { }
}
