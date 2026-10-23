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
    private com.aetheris.governance.security.ResourceAuthorization authorization = new com.aetheris.governance.security.ResourceAuthorization();
    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthorization(com.aetheris.governance.security.ResourceAuthorization authorization) { this.authorization = authorization; }

    private final GovernancePolicyService service;

    public GovernancePolicyController(GovernancePolicyService service) {
        this.service = service;
    }

    @GetMapping
    public List<GovernancePolicy> list() { return authorization.filter("governance_policy", service.list(), GovernancePolicy::getId); }

    @GetMapping("/{id}")
    public ResponseEntity<GovernancePolicy> getById(@PathVariable Long id) {
        authorization.require("governance_policy", id, "view");
        GovernancePolicy policy = service.getById(id);
        return policy != null ? ResponseEntity.ok(policy) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public GovernancePolicy create(@org.springframework.validation.annotation.Validated({jakarta.validation.groups.Default.class,
            com.aetheris.governance.validation.CreateDefinition.class}) @RequestBody GovernancePolicy policy) {
        authorization.require("governance_policy", null, "create");
        authorization.requireManage();
        policy.setId(null);
        service.save(policy);
        return policy;
    }

    @PutMapping("/{id}")
    public ResponseEntity<GovernancePolicy> update(@PathVariable Long id, @Valid @RequestBody GovernancePolicy policy) {
        authorization.require("governance_policy", id, "update");
        authorization.protectDefinition(policy,service.getById(id),java.util.Set.of("name","description","status"));
        policy.setId(id);
        authorization.retainEditable(policy,java.util.Set.of("name","description","status"));
        boolean updated = service.updateById(policy);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        authorization.require("governance_policy", id, "delete");
        boolean removed = service.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
