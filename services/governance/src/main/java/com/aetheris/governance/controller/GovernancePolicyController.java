package com.aetheris.governance.controller;

import com.aetheris.governance.entity.GovernancePolicy;
import com.aetheris.governance.service.GovernancePolicyService;
import com.aetheris.security.protocol.UserProof;
import com.aetheris.security.protocol.pagination.VisibleListCursor;
import com.aetheris.security.protocol.pagination.VisibleListPage;
import com.aetheris.security.protocol.pagination.VisibleListScanner;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.LinkedHashSet;

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
    public List<GovernancePolicy> list() {
        try {
            var page = page(200, null);
            if (page.hasMore()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "列表超过兼容上限，请使用分页接口");
            return page.items();
        } catch (ResponseStatusException failure) {
            // Direct unit callers have no servlet subject; keep the deny-only
            // legacy behavior without weakening an actual HTTP request.
            if (failure.getStatusCode() == HttpStatus.UNAUTHORIZED && RequestContextHolder.getRequestAttributes() == null) {
                return authorization.filter("governance_policy", service.list(), GovernancePolicy::getId);
            }
            throw failure;
        }
    }

    /** Keyset page; authorization is rechecked for every candidate window. */
    @GetMapping("/page")
    public VisibleListPage<GovernancePolicy> page(
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor) {
        var trusted = authorization.currentUser();
        if (trusted == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要已验签用户");
        var proof = trusted.proof();
        String hash = pageHash("governance_policy", proof);
        long upper = maxId();
        return VisibleListScanner.page(cursor, size, hash, upper,
                (frozenUpper, before, limit) -> service.list(new LambdaQueryWrapper<GovernancePolicy>()
                        .le(GovernancePolicy::getId, frozenUpper)
                        .lt(before != null, GovernancePolicy::getId, before)
                        .orderByDesc(GovernancePolicy::getId)
                        .last("LIMIT " + limit)),
                GovernancePolicy::getId,
                rows -> new LinkedHashSet<>(authorization.filter("governance_policy", rows, GovernancePolicy::getId)
                        .stream().map(GovernancePolicy::getId).toList()));
    }

    private long maxId() {
        var row = service.getOne(new LambdaQueryWrapper<GovernancePolicy>()
                .select(GovernancePolicy::getId).orderByDesc(GovernancePolicy::getId).last("LIMIT 1"));
        return row == null || row.getId() == null ? 0 : row.getId();
    }

    private static String pageHash(String kind, UserProof proof, Object... filters) {
        StringBuilder value = new StringBuilder(kind).append('\u0000')
                .append(proof.uid()).append('\u0000').append(proof.username()).append('\u0000')
                .append(proof.accessVersion());
        for (Object filter : filters) {
            if (filter == null) { value.append("\u0000N"); continue; }
            String text = String.valueOf(filter);
            value.append('\u0000').append('V').append(text.length()).append(':').append(text);
        }
        return VisibleListCursor.hash(value.toString());
    }

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
