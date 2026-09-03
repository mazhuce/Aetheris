package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.BusinessDomain;
import com.aetheris.metadata.mapper.BusinessDomainMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class BusinessDomainService {
    private final BusinessDomainMapper mapper;
    private final JdbcTemplate jdbc;

    public BusinessDomainService(BusinessDomainMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    public List<BusinessDomain> list(String keyword, String status) {
        LambdaQueryWrapper<BusinessDomain> query = new LambdaQueryWrapper<BusinessDomain>()
                .and(StringUtils.hasText(keyword), q -> q.like(BusinessDomain::getDomainCode, keyword.trim())
                        .or().like(BusinessDomain::getDomainName, keyword.trim()))
                .eq(StringUtils.hasText(status), BusinessDomain::getStatus, status)
                .orderByAsc(BusinessDomain::getDomainCode);
        return mapper.selectList(query);
    }

    public BusinessDomain get(Long id) { return mapper.selectById(id); }

    @Transactional
    public BusinessDomain create(BusinessDomain domain, String username) {
        normalize(domain);
        ensureUnique(domain, null);
        domain.setId(null);
        domain.setCreatedBy(username);
        domain.setCreatedAt(null);
        domain.setUpdatedAt(null);
        mapper.insert(domain);
        return domain;
    }

    @Transactional
    public BusinessDomain update(Long id, BusinessDomain input) {
        BusinessDomain existing = require(id);
        normalize(input);
        if (!existing.getDomainCode().equals(input.getDomainCode())) {
            throw new IllegalStateException("业务域编码是稳定标识，创建后不能修改");
        }
        ensureUnique(input, id);
        input.setId(id);
        input.setCreatedBy(existing.getCreatedBy());
        input.setCreatedAt(existing.getCreatedAt());
        input.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(input);
        // 兼容历史展示字段；业务关联始终以外键为准。
        jdbc.update("UPDATE metadata.metadata_table SET domain = ? WHERE business_domain_id = ?", input.getDomainName(), id);
        jdbc.update("UPDATE metadata.metric_definition SET business_domain = ? WHERE business_domain_id = ?", input.getDomainName(), id);
        jdbc.update("UPDATE metadata.logical_model SET business_domain = ? WHERE business_domain_id = ?", input.getDomainName(), id);
        return require(id);
    }

    @Transactional
    public void delete(Long id) {
        BusinessDomain existing = require(id);
        if ("UNASSIGNED".equals(existing.getDomainCode())) {
            throw new IllegalStateException("系统保留的未归属域不能删除");
        }
        Long references = jdbc.queryForObject("""
                SELECT
                    (SELECT count(*) FROM metadata.metadata_table WHERE business_domain_id = ?) +
                    (SELECT count(*) FROM metadata.logical_model WHERE business_domain_id = ?) +
                    (SELECT count(*) FROM metadata.metric_definition WHERE business_domain_id = ?) +
                    (SELECT count(*) FROM metadata.semantic_dimension WHERE business_domain_id = ?)
                """, Long.class, id, id, id, id);
        if (references != null && references > 0) {
            throw new IllegalStateException("业务域仍被数据资产、模型、维度或指标引用，请先迁移关联对象");
        }
        mapper.deleteById(id);
    }

    public BusinessDomain require(Long id) {
        BusinessDomain domain = mapper.selectById(id);
        if (domain == null) throw new NotFoundException("业务域不存在");
        return domain;
    }

    public BusinessDomain requireActive(Long id) {
        BusinessDomain domain = require(id);
        if (!"active".equals(domain.getStatus())) throw new IllegalStateException("业务域已停用，不能发布新的语义版本");
        return domain;
    }

    private void normalize(BusinessDomain domain) {
        domain.setDomainCode(domain.getDomainCode().trim().toUpperCase(Locale.ROOT));
        domain.setDomainName(domain.getDomainName().trim());
        domain.setStatus(StringUtils.hasText(domain.getStatus()) ? domain.getStatus().trim().toLowerCase(Locale.ROOT) : "active");
        if (!List.of("active", "disabled").contains(domain.getStatus())) {
            throw new IllegalArgumentException("业务域状态仅支持 active 或 disabled");
        }
    }

    private void ensureUnique(BusinessDomain domain, Long excludeId) {
        long count = mapper.selectCount(new LambdaQueryWrapper<BusinessDomain>()
                .and(q -> q.eq(BusinessDomain::getDomainCode, domain.getDomainCode())
                        .or().eq(BusinessDomain::getDomainName, domain.getDomainName()))
                .ne(excludeId != null, BusinessDomain::getId, excludeId));
        if (count > 0) throw new IllegalStateException("业务域编码或名称已存在");
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
}
