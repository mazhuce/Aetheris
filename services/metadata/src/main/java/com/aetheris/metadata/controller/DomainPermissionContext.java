package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.BusinessDomain;
import com.aetheris.metadata.service.BusinessDomainService;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Context comes from the persisted domain, never request JSON or query parameters. */
@Component
public class DomainPermissionContext {
    private final BusinessDomainService domains;
    public DomainPermissionContext(BusinessDomainService domains) { this.domains = domains; }
    public Map<String,String> of(Long id) {
        if (id == null) return Map.of();
        BusinessDomain domain = domains.get(id);
        if (domain == null || !id.equals(domain.getId())) return Map.of();
        return of(domain);
    }
    static Map<String,String> of(BusinessDomain domain) {
        if (domain == null || domain.getId() == null) return Map.of();
        return domain.getDomainCode() == null || domain.getDomainCode().isBlank()
                ? Map.of("domainId", domain.getId().toString())
                : Map.of("domainId", domain.getId().toString(), "domainCode", domain.getDomainCode());
    }
}
