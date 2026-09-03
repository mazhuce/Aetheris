package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.MetricQueryRequest;
import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.mapper.MetricDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** 指标聚合根：定义、校验、查询、发布和不可变版本快照。 */
@Service
public class MetricDefinitionService {
    private static final List<String> LAYERS = List.of("atomic", "derived", "composite");
    private final MetricDefinitionMapper mapper;
    private final BusinessDomainService domainService;
    private final LogicalModelService logicalModelService;
    private final SemanticMetricQueryService queryService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MetricDefinitionService(MetricDefinitionMapper mapper, BusinessDomainService domainService,
                                   LogicalModelService logicalModelService, SemanticMetricQueryService queryService,
                                   JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.domainService = domainService;
        this.logicalModelService = logicalModelService;
        this.queryService = queryService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<MetricDefinition> list(String keyword, String status) {
        return list(keyword, status, null);
    }

    /**
     * @param domainIds 业务域 id 白名单（权限收窄列表过滤）；null 表示不过滤，
     *                  空集合时返回空列表。
     */
    public List<MetricDefinition> list(String keyword, String status, Collection<Long> domainIds) {
        if (domainIds != null && domainIds.isEmpty()) {
            return List.of();
        }
        if ("published".equals(status)) return published(keyword, domainIds);
        LambdaQueryWrapper<MetricDefinition> wrapper = new LambdaQueryWrapper<MetricDefinition>()
                .eq(StringUtils.hasText(status), MetricDefinition::getStatus, status)
                .in(domainIds != null, MetricDefinition::getBusinessDomainId, domainIds)
                .orderByDesc(MetricDefinition::getUpdatedAt);
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(MetricDefinition::getMetricName, trimmed)
                    .or().like(MetricDefinition::getMetricCode, trimmed)
                    .or().like(MetricDefinition::getBusinessDomain, trimmed));
        }
        return mapper.selectList(wrapper);
    }

    public List<MetricDefinition> list(String keyword) { return list(keyword, null); }

    public List<MetricDefinition> published(String keyword) {
        return published(keyword, null);
    }

    public List<MetricDefinition> published(String keyword, Collection<Long> domainIds) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        return mapper.selectList(new LambdaQueryWrapper<MetricDefinition>()
                        .isNotNull(MetricDefinition::getPublishedVersion)
                        .ne(MetricDefinition::getStatus, "deprecated")
                        .orderByAsc(MetricDefinition::getMetricCode)).stream()
                .map(this::resolvePublished)
                .filter(metric -> domainIds == null || domainIds.contains(metric.getBusinessDomainId()))
                .filter(metric -> normalizedKeyword == null || matches(metric, normalizedKeyword))
                .toList();
    }

    public MetricDefinition getById(Long id) { return mapper.selectById(id); }
    public MetricDefinition require(Long id) {
        MetricDefinition metric = getById(id);
        if (metric == null) throw new NotFoundException("指标不存在");
        return metric;
    }
    public MetricDefinition requirePublished(String code) {
        MetricDefinition metric = mapper.selectOne(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getMetricCode, normalizeCode(code)));
        if (metric == null || metric.getPublishedVersion() == null || "deprecated".equals(metric.getStatus())) {
            throw new NotFoundException("指标未发布或不存在：" + code);
        }
        return resolvePublished(metric);
    }

    public boolean codeExists(String code, Long excludeId) {
        return mapper.selectCount(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getMetricCode, normalizeCode(code))
                .ne(excludeId != null, MetricDefinition::getId, excludeId)) > 0;
    }

    @Transactional
    public MetricDefinition create(MetricDefinition metric, String username) {
        normalize(metric);
        if (codeExists(metric.getMetricCode(), null)) throw new IllegalStateException("指标编码已存在：" + metric.getMetricCode());
        validateStableReferences(metric);
        metric.setId(null);
        metric.setStatus("draft");
        metric.setVersion(1);
        metric.setPublishedVersion(null);
        metric.setCreatedBy(username);
        metric.setPublishedBy(null);
        metric.setPublishedAt(null);
        mapper.insert(metric);
        return metric;
    }

    @Transactional
    public MetricDefinition update(Long id, MetricDefinition input) {
        MetricDefinition existing = require(id);
        requireDraft(existing);
        normalize(input);
        requireStableCode(existing.getMetricCode(), input.getMetricCode());
        if (codeExists(input.getMetricCode(), id)) throw new IllegalStateException("指标编码已存在：" + input.getMetricCode());
        validateStableReferences(input);
        input.setId(id);
        input.setStatus("draft");
        input.setVersion(existing.getVersion());
        input.setPublishedVersion(existing.getPublishedVersion());
        input.setCreatedBy(existing.getCreatedBy());
        input.setCreatedAt(existing.getCreatedAt());
        input.setPublishedBy(existing.getPublishedBy());
        input.setPublishedAt(existing.getPublishedAt());
        input.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(input);
        return require(id);
    }

    public Map<String, Object> validate(Long id) { return queryService.validate(require(id)); }
    public Map<String, Object> preview(Long id, MetricQueryRequest request) { return queryService.query(require(id), request); }
    public Map<String, Object> query(String code, MetricQueryRequest request) { return queryService.query(requirePublished(code), request); }

    @Transactional
    public MetricDefinition publish(Long id, String username) {
        MetricDefinition metric = require(id);
        requireDraft(metric);
        if (metric.getBusinessDomainId() != null) domainService.requireActive(metric.getBusinessDomainId());
        queryService.validate(metric);
        OffsetDateTime now = OffsetDateTime.now();
        metric.setStatus("published");
        metric.setPublishedVersion(metric.getVersion());
        metric.setPublishedBy(username);
        metric.setPublishedAt(now);
        metric.setUpdatedAt(now);
        mapper.updateById(metric);
        jdbc.update("""
                INSERT INTO metadata.metric_definition_version
                    (metric_definition_id, version_no, snapshot_json, published_by, published_at)
                VALUES (?, ?, ?::jsonb, ?, ?)
                """, id, metric.getVersion(), snapshot(metric), username, now);
        return require(id);
    }

    @Transactional
    public MetricDefinition newVersion(Long id) {
        MetricDefinition metric = require(id);
        if (!List.of("published", "deprecated").contains(metric.getStatus())) {
            throw new IllegalStateException("只有已发布或已废弃指标可以创建新版本");
        }
        metric.setVersion(metric.getVersion() + 1);
        metric.setStatus("draft");
        metric.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(metric);
        return require(id);
    }

    @Transactional
    public MetricDefinition discardDraft(Long id) {
        MetricDefinition current = require(id);
        requireDraft(current);
        if (current.getPublishedVersion() == null) throw new IllegalStateException("首个未发布草稿不能回退，可直接删除");
        MetricDefinition published = resolvePublished(current);
        published.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(published);
        return require(id);
    }

    @Transactional
    public MetricDefinition deprecate(Long id) {
        MetricDefinition metric = require(id);
        if (metric.getPublishedVersion() == null) throw new IllegalStateException("只有存在在线版本的指标可以废弃");
        metric.setStatus("deprecated");
        metric.setPublishedVersion(null);
        metric.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(metric);
        return require(id);
    }

    @Transactional
    public void remove(Long id) {
        MetricDefinition metric = require(id);
        requireDraft(metric);
        Long versions = jdbc.queryForObject("SELECT count(*) FROM metadata.metric_definition_version WHERE metric_definition_id = ?", Long.class, id);
        if (versions != null && versions > 0) throw new IllegalStateException("已有发布历史的指标不能删除，请使用废弃流程");
        mapper.deleteById(id);
    }

    public List<SemanticVersion> versions(Long id) {
        require(id);
        return jdbc.query("""
                SELECT id, version_no, snapshot_json, published_by, published_at
                FROM metadata.metric_definition_version WHERE metric_definition_id = ? ORDER BY version_no DESC
                """, this::mapVersion, id);
    }

    private void normalize(MetricDefinition metric) {
        metric.setMetricCode(normalizeCode(metric.getMetricCode()));
        metric.setMetricName(metric.getMetricName().trim());
        String layer = StringUtils.hasText(metric.getLayer()) ? metric.getLayer().trim().toLowerCase(Locale.ROOT) : "atomic";
        if (!LAYERS.contains(layer)) throw new IllegalArgumentException("不支持的指标层级");
        metric.setLayer(layer);
        metric.setAggregation(StringUtils.hasText(metric.getAggregation()) ? metric.getAggregation().trim().toUpperCase(Locale.ROOT) : "COUNT");
        metric.setDefaultTimeGrain(StringUtils.hasText(metric.getDefaultTimeGrain()) ? metric.getDefaultTimeGrain().trim().toLowerCase(Locale.ROOT) : "none");
        if (!StringUtils.hasText(metric.getFiltersJson())) metric.setFiltersJson("[]");
        if (!StringUtils.hasText(metric.getDimensionIdsJson())) metric.setDimensionIdsJson("[]");
        if (metric.getBusinessDomainId() != null) {
            var domain = domainService.require(metric.getBusinessDomainId());
            metric.setBusinessDomain(domain.getDomainName());
        }
    }

    private void validateStableReferences(MetricDefinition metric) {
        if (metric.getBusinessDomainId() != null) domainService.require(metric.getBusinessDomainId());
        if (metric.getLogicalModelId() != null) logicalModelService.require(metric.getLogicalModelId());
    }
    private void requireDraft(MetricDefinition metric) {
        if (!"draft".equals(metric.getStatus())) throw new IllegalStateException("已发布或已废弃版本不可直接修改，请先创建新版本");
    }
    private void requireStableCode(String existing, String incoming) {
        if (!existing.equals(incoming)) throw new IllegalStateException("指标编码是稳定标识，创建后不能修改");
    }
    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("指标编码不能为空");
        return code.trim().toUpperCase(Locale.ROOT);
    }
    private String snapshot(MetricDefinition metric) {
        try {
            return objectMapper.writeValueAsString(Map.ofEntries(
                    Map.entry("metricCode", metric.getMetricCode()), Map.entry("metricName", metric.getMetricName()),
                    Map.entry("businessDomainId", zero(metric.getBusinessDomainId())), Map.entry("logicalModelId", zero(metric.getLogicalModelId())),
                    Map.entry("sourceTableId", zero(metric.getSourceTableId())), Map.entry("layer", text(metric.getLayer())),
                    Map.entry("calibreFormula", text(metric.getCalibreFormula())), Map.entry("aggregation", text(metric.getAggregation())),
                    Map.entry("measureColumn", text(metric.getMeasureColumn())), Map.entry("filters", parse(metric.getFiltersJson())),
                    Map.entry("timeColumn", text(metric.getTimeColumn())), Map.entry("defaultTimeGrain", text(metric.getDefaultTimeGrain())),
                    Map.entry("dimensionIds", parse(metric.getDimensionIdsJson())), Map.entry("unit", text(metric.getUnit())),
                    Map.entry("owner", text(metric.getOwner())), Map.entry("description", text(metric.getDescription()))));
        } catch (JsonProcessingException error) { throw new IllegalStateException("无法生成指标版本快照", error); }
    }
    private Object parse(String value) throws JsonProcessingException { return objectMapper.readTree(value == null ? "[]" : value); }
    private long zero(Long value) { return value == null ? 0L : value; }
    private String text(String value) { return value == null ? "" : value; }
    private SemanticVersion mapVersion(ResultSet rs, int row) throws SQLException {
        try {
            return new SemanticVersion(rs.getLong("id"), rs.getInt("version_no"), objectMapper.readTree(rs.getString("snapshot_json")),
                    rs.getString("published_by"), rs.getObject("published_at", OffsetDateTime.class));
        } catch (JsonProcessingException error) { throw new SQLException("指标版本快照损坏", error); }
    }

    /** 草稿与线上版本并行存在时，从不可变快照恢复线上口径。 */
    private MetricDefinition resolvePublished(MetricDefinition aggregate) {
        SemanticVersion active = jdbc.query("""
                SELECT id, version_no, snapshot_json, published_by, published_at
                FROM metadata.metric_definition_version
                WHERE metric_definition_id = ? AND version_no = ?
                """, this::mapVersion, aggregate.getId(), aggregate.getPublishedVersion()).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("指标在线版本快照缺失：" + aggregate.getMetricCode()));
        JsonNode node = active.snapshot();
        MetricDefinition metric = new MetricDefinition();
        metric.setId(aggregate.getId());
        metric.setMetricCode(node.path("metricCode").asText(aggregate.getMetricCode()));
        metric.setMetricName(node.path("metricName").asText(aggregate.getMetricName()));
        metric.setBusinessDomainId(nullableId(node.path("businessDomainId")));
        metric.setBusinessDomain(metric.getBusinessDomainId() == null ? null
                : domainService.require(metric.getBusinessDomainId()).getDomainName());
        metric.setLogicalModelId(nullableId(node.path("logicalModelId")));
        metric.setSourceTableId(nullableId(node.path("sourceTableId")));
        metric.setLayer(node.path("layer").asText("atomic"));
        metric.setCalibreFormula(node.path("calibreFormula").asText(""));
        metric.setAggregation(node.path("aggregation").asText("COUNT"));
        metric.setMeasureColumn(nullableText(node.path("measureColumn")));
        metric.setFiltersJson(node.path("filters").toString());
        metric.setTimeColumn(nullableText(node.path("timeColumn")));
        metric.setDefaultTimeGrain(node.path("defaultTimeGrain").asText("none"));
        metric.setDimensionIdsJson(node.path("dimensionIds").toString());
        metric.setUnit(nullableText(node.path("unit")));
        metric.setOwner(nullableText(node.path("owner")));
        metric.setDescription(nullableText(node.path("description")));
        metric.setStatus("published");
        metric.setVersion(active.versionNo());
        metric.setPublishedVersion(active.versionNo());
        metric.setCreatedBy(aggregate.getCreatedBy());
        metric.setPublishedBy(active.publishedBy());
        metric.setPublishedAt(active.publishedAt());
        metric.setCreatedAt(aggregate.getCreatedAt());
        metric.setUpdatedAt(aggregate.getUpdatedAt());
        return metric;
    }

    private boolean matches(MetricDefinition metric, String keyword) {
        return Stream.of(metric.getMetricCode(), metric.getMetricName(), metric.getBusinessDomain())
                .filter(value -> value != null)
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(keyword));
    }
    private Long nullableId(JsonNode node) { long value = node.asLong(0); return value > 0 ? value : null; }
    private String nullableText(JsonNode node) { String value = node.asText(""); return value.isBlank() ? null : value; }

    public static class NotFoundException extends RuntimeException { public NotFoundException(String message) { super(message); } }
}
