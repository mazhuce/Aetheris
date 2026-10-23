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
    @org.springframework.beans.factory.annotation.Value("${aetheris.list.compat-max-candidates:100000}")
    private int compatMaxCandidates=100000;
    @org.springframework.beans.factory.annotation.Value("${aetheris.list.compat-max-rows:20000}")
    private int compatMaxRows=20000;
    private static final List<String> LAYERS = List.of("atomic", "derived", "composite");
    public static final int MAX_PAGE_SIZE = 500;
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
        return list(keyword, status, null, 1, MAX_PAGE_SIZE);
    }

    /**
     * @param domainIds 业务域 id 白名单（权限收窄列表过滤）；null 表示不过滤，
     *                  空集合时返回空列表。
     */
    public List<MetricDefinition> list(String keyword, String status, Collection<Long> domainIds) {
        return list(keyword, status, domainIds, 1, MAX_PAGE_SIZE);
    }

    public List<MetricDefinition> list(String keyword, String status, Collection<Long> domainIds,
                                       int page, int size) {
        validatePage(page, size);
        if (domainIds != null && domainIds.isEmpty()) {
            return List.of();
        }
        if ("published".equals(status)) return published(keyword, domainIds, page, size);
        long offset = (long) (page - 1) * size;
        LambdaQueryWrapper<MetricDefinition> wrapper = new LambdaQueryWrapper<MetricDefinition>()
                .eq(StringUtils.hasText(status), MetricDefinition::getStatus, status)
                .in(domainIds != null, MetricDefinition::getBusinessDomainId, domainIds)
                .orderByDesc(MetricDefinition::getUpdatedAt)
                .orderByDesc(MetricDefinition::getId)
                .last("LIMIT " + size + " OFFSET " + offset);
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
        return published(keyword, domainIds, 1, MAX_PAGE_SIZE);
    }

    public List<MetricDefinition> published(String keyword, Collection<Long> domainIds, int page, int size) {
        validatePage(page, size);
        if(domainIds!=null && domainIds.isEmpty()) return List.of();
        long from=(long)(page-1)*size, end=from+size;
        if(end>compatMaxRows) throw new VisibleListPager.ListLimitException();
        String key=com.aetheris.metadata.mapper.VisibleListMapper.normalizeKeyword(keyword).toLowerCase(Locale.ROOT);
        List<MetricDefinition> result=new java.util.ArrayList<>();long matched=0;
        for(int scanned=0;scanned<compatMaxCandidates;scanned+=MAX_PAGE_SIZE) {
            var wrapper=new LambdaQueryWrapper<MetricDefinition>().isNotNull(MetricDefinition::getPublishedVersion)
                    .ne(MetricDefinition::getStatus,"deprecated").orderByAsc(MetricDefinition::getMetricCode).orderByAsc(MetricDefinition::getId)
                    .last("LIMIT "+Math.min(MAX_PAGE_SIZE,compatMaxCandidates-scanned)+" OFFSET "+scanned);
            var aggregates=mapper.selectList(wrapper);
            var snapshots=loadPublishedVersions(aggregates);
            var candidates=aggregates.stream().map(aggregate -> new com.aetheris.metadata.mapper.VisibleListMapper.MetricCandidate(
                    aggregate,snapshots.get(aggregate.getId()+":"+aggregate.getPublishedVersion()))).toList();
            for(var metric:resolvePublishedBatch(candidates)) {
                if(domainIds!=null && (metric.getBusinessDomainId()==null || !domainIds.contains(metric.getBusinessDomainId())) || !key.isEmpty() && !matches(metric,key)) continue;
                if(matched++>=from) result.add(metric);
                if(result.size()==size) return result;
            }
            if(aggregates.size()<Math.min(MAX_PAGE_SIZE,compatMaxCandidates-scanned)) return result;
        }
        throw new VisibleListPager.ListLimitException();
    }

    private void validatePage(int page, int size) {
        if (page < 1) throw new IllegalArgumentException("page 必须大于等于 1");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
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
    /** Execute the same immutable published snapshot that the caller has authorized. */
    public Map<String,Object> queryPublished(MetricDefinition snapshot,MetricQueryRequest request){
        if(snapshot==null||snapshot.getId()==null||!"published".equals(snapshot.getStatus())||snapshot.getPublishedVersion()==null
                ||!snapshot.getPublishedVersion().equals(snapshot.getVersion()))throw new IllegalArgumentException("指标未发布");
        return queryService.query(snapshot,request);
    }

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
        JsonNode node = validatedSnapshot(aggregate,active);
        MetricDefinition metric = new MetricDefinition();
        metric.setId(aggregate.getId());
        metric.setMetricCode(node.get("metricCode").textValue());
        metric.setMetricName(node.get("metricName").textValue());
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

    private Map<String, SemanticVersion> loadPublishedVersions(List<MetricDefinition> aggregates) {
        if (aggregates.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(aggregates.size(), "?"));
        Object[] args = aggregates.stream().map(MetricDefinition::getId).toArray();
        return jdbc.query("SELECT v.metric_definition_id, v.id, v.version_no, v.snapshot_json, v.published_by, v.published_at "
                        + "FROM metadata.metric_definition_version v JOIN metadata.metric_definition m ON m.id=v.metric_definition_id AND m.published_version=v.version_no WHERE v.metric_definition_id IN (" + placeholders + ")",
                (rs, row) -> Map.entry(rs.getLong("metric_definition_id"), mapVersion(rs, row)), args)
                .stream().collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey() + ":" + e.getValue().versionNo(), Map.Entry::getValue));
    }

    List<MetricDefinition> resolvePublishedBatch(List<com.aetheris.metadata.mapper.VisibleListMapper.MetricCandidate> candidates) {
        var domainIds=candidates.stream().map(candidate -> {
            var node=validatedSnapshot(candidate.aggregate(),candidate.version());
            var domain=node.get("businessDomainId");
            return domain==null || domain.isNull() || domain.longValue()==0?null:domain.longValue();
        }).filter(java.util.Objects::nonNull).distinct().toList();
        var domains=new com.aetheris.metadata.mapper.VisibleListMapper(jdbc,objectMapper).domains(domainIds);
        return candidates.stream().map(candidate -> resolvePublished(candidate.aggregate(),candidate.version(),domains)).toList();
    }

    private MetricDefinition resolvePublished(MetricDefinition aggregate, SemanticVersion active,
            Map<Long,com.aetheris.metadata.mapper.VisibleListMapper.DomainContext> domains) {
        JsonNode node = validatedSnapshot(aggregate,active);
        MetricDefinition metric = new MetricDefinition();
        metric.setId(aggregate.getId());
        metric.setMetricCode(node.get("metricCode").textValue());
        metric.setMetricName(node.get("metricName").textValue());
        metric.setBusinessDomainId(nullableId(node.path("businessDomainId")));
        var domain=metric.getBusinessDomainId()==null?null:domains.get(metric.getBusinessDomainId());
        metric.setBusinessDomain(domain==null?null:domain.name());
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

    private JsonNode validatedSnapshot(MetricDefinition aggregate,SemanticVersion active) {
        if(active==null || active.versionNo()==null || !active.versionNo().equals(aggregate.getPublishedVersion()))
            throw new com.aetheris.metadata.mapper.VisibleListMapper.InvalidSnapshotException();
        var node=active.snapshot();
        if(node==null || !node.isObject()) throw new com.aetheris.metadata.mapper.VisibleListMapper.InvalidSnapshotException();
        var code=node.get("metricCode");var name=node.get("metricName");var domain=node.get("businessDomainId");
        if(code==null || !code.isTextual() || code.textValue().isBlank() || !code.textValue().equals(aggregate.getMetricCode())
                || name==null || !name.isTextual() || name.textValue().isBlank()
                || domain!=null && !domain.isNull() && (!domain.isIntegralNumber() || !domain.canConvertToLong() || domain.longValue()<0))
            throw new com.aetheris.metadata.mapper.VisibleListMapper.InvalidSnapshotException();
        return node;
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
