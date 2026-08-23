package com.aetheris.governance.controller;

import com.aetheris.governance.entity.GovernancePolicy;
import com.aetheris.governance.service.GovernancePolicyService;
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
@RequestMapping("/api/governance/policies")
public class GovernancePolicyController {

    private final GovernancePolicyService service;

    public GovernancePolicyController(GovernancePolicyService service) {
        this.service = service;
    }

    @GetMapping
    public List<GovernancePolicy> list() { return service.list(); }

    @GetMapping("/{id}")
    public ResponseEntity<GovernancePolicy> getById(@PathVariable Long id) {
        GovernancePolicy policy = service.getById(id);
        return policy != null ? ResponseEntity.ok(policy) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public GovernancePolicy create(@Valid @RequestBody GovernancePolicy policy) {
        service.save(policy);
        return policy;
    }

    @PutMapping("/{id}")
    public ResponseEntity<GovernancePolicy> update(@PathVariable Long id, @Valid @RequestBody GovernancePolicy policy) {
        policy.setId(id);
        boolean updated = service.updateById(policy);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = service.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
