package com.aetheris.quality.controller;

import com.aetheris.quality.entity.QualityRule;
import com.aetheris.quality.service.QualityRuleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/quality/rules")
public class QualityRuleController {

    private final QualityRuleService qualityRuleService;

    public QualityRuleController(QualityRuleService qualityRuleService) {
        this.qualityRuleService = qualityRuleService;
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
        qualityRuleService.save(rule);
        return rule;
    }

    @PutMapping("/{id}")
    public ResponseEntity<QualityRule> update(
            @PathVariable Long id,
            @Valid @RequestBody QualityRule rule) {
        rule.setId(id);
        boolean updated = qualityRuleService.updateById(rule);
        return updated ? ResponseEntity.ok(qualityRuleService.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = qualityRuleService.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
