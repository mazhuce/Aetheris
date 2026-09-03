package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.DataSourceColumnDto;
import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.entity.SemanticDimension;
import com.aetheris.metadata.mapper.SemanticDimensionMapper;
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

@Service
public class SemanticDimensionService {
    private static final List<String> TYPES = List.of("categorical", "time", "geography");
    private final SemanticDimensionMapper mapper;
    private final MetadataTableService tableService;
    private final DataSourceService dataSourceService;
    private final BusinessDomainService domainService;
    private final JdbcService jdbcService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SemanticDimensionService(
            SemanticDimensionMapper mapper,
            MetadataTableService tableService,
            DataSourceService dataSourceService,
            BusinessDomainService domainService,
            JdbcService jdbcService,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.tableService = tableService;
        this.dataSourceService = dataSourceService;
        this.domainService = domainService;
        this.jdbcService = jdbcService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<SemanticDimension> list(String keyword, String status, Long tableId) {
        return list(keyword, status, tableId, null);
    }

    /**
     * @param domainIds 业务域 id 白名单（权限收窄列表过滤）；null 表示不过滤，
     *                  空集合时返回空列表。
     */
    public List<SemanticDimension> list(String keyword, String status, Long tableId, Collection<Long> domainIds) {
        if (domainIds != null && domainIds.isEmpty()) {
            return List.of();
        }
        if ("published".equals(status)) return published(keyword, tableId, domainIds);
        LambdaQueryWrapper<SemanticDimension> query = new LambdaQueryWrapper<SemanticDimension>()
                .and(StringUtils.hasText(keyword), q -> q.like(SemanticDimension::getDimensionCode, keyword.trim())
                        .or().like(SemanticDimension::getDimensionName, keyword.trim()))
                .eq(StringUtils.hasText(status), SemanticDimension::getStatus, status)
                .eq(tableId != null, SemanticDimension::getMetadataTableId, tableId)
                .in(domainIds != null, SemanticDimension::getBusinessDomainId, domainIds)
                .orderByDesc(SemanticDimension::getUpdatedAt);
        return mapper.selectList(query);
    }

    public SemanticDimension get(Long id) { return mapper.selectById(id); }

    public SemanticDimension require(Long id) {
        SemanticDimension dimension = get(id);
        if (dimension == null) throw new NotFoundException("语义维度不存在");
        return dimension;
    }

    public SemanticDimension requirePublished(Long id) {
        SemanticDimension dimension = require(id);
        if (dimension.getPublishedVersion() == null || "deprecated".equals(dimension.getStatus())) {
            throw new IllegalArgumentException("维度未发布：" + dimension.getDimensionCode());
        }
        return resolvePublished(dimension);
    }

    public SemanticDimension findPublishedByCode(String code) {
        SemanticDimension dimension = mapper.selectOne(new LambdaQueryWrapper<SemanticDimension>()
                .eq(SemanticDimension::getDimensionCode, normalizeCode(code)));
        return dimension == null || dimension.getPublishedVersion() == null || "deprecated".equals(dimension.getStatus())
                ? null : resolvePublished(dimension);
    }

    @Transactional
    public SemanticDimension create(SemanticDimension dimension, String username) {
        normalize(dimension);
        ensureUnique(dimension.getDimensionCode(), null);
        validateReferences(dimension, false);
        dimension.setId(null);
        dimension.setStatus("draft");
        dimension.setVersion(1);
        dimension.setPublishedVersion(null);
        dimension.setCreatedBy(username);
        dimension.setPublishedBy(null);
        dimension.setPublishedAt(null);
        mapper.insert(dimension);
        return dimension;
    }

    @Transactional
    public SemanticDimension update(Long id, SemanticDimension input) {
        SemanticDimension existing = require(id);
        requireDraft(existing);
        normalize(input);
        requireStableCode(existing.getDimensionCode(), input.getDimensionCode());
        ensureUnique(input.getDimensionCode(), id);
        validateReferences(input, false);
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

    @Transactional
    public SemanticDimension publish(Long id, String username) {
        SemanticDimension dimension = require(id);
        requireDraft(dimension);
        validateReferences(dimension, true);
        OffsetDateTime now = OffsetDateTime.now();
        dimension.setStatus("published");
        dimension.setPublishedVersion(dimension.getVersion());
        dimension.setPublishedBy(username);
        dimension.setPublishedAt(now);
        dimension.setUpdatedAt(now);
        mapper.updateById(dimension);
        jdbc.update("""
                INSERT INTO metadata.semantic_dimension_version
                    (semantic_dimension_id, version_no, snapshot_json, published_by, published_at)
                VALUES (?, ?, ?::jsonb, ?, ?)
                """, id, dimension.getVersion(), json(snapshot(dimension)), username, now);
        return require(id);
    }

    @Transactional
    public SemanticDimension newVersion(Long id) {
        SemanticDimension dimension = require(id);
        if (!List.of("published", "deprecated").contains(dimension.getStatus())) {
            throw new IllegalStateException("只有已发布或已废弃维度可以创建新版本");
        }
        dimension.setVersion(dimension.getVersion() + 1);
        dimension.setStatus("draft");
        dimension.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(dimension);
        return require(id);
    }

    @Transactional
    public SemanticDimension discardDraft(Long id) {
        SemanticDimension current = require(id);
        requireDraft(current);
        if (current.getPublishedVersion() == null) throw new IllegalStateException("首个未发布草稿不能回退，可直接删除");
        SemanticDimension published = resolvePublished(current);
        published.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(published);
        return require(id);
    }

    @Transactional
    public SemanticDimension deprecate(Long id) {
        SemanticDimension dimension = require(id);
        if (dimension.getPublishedVersion() == null) {
            throw new IllegalStateException("只有存在在线版本的维度可以废弃");
        }
        dimension.setStatus("deprecated");
        dimension.setPublishedVersion(null);
        dimension.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(dimension);
        return require(id);
    }

    @Transactional
    public void delete(Long id) {
        SemanticDimension dimension = require(id);
        requireDraft(dimension);
        Long versions = jdbc.queryForObject(
                "SELECT count(*) FROM metadata.semantic_dimension_version WHERE semantic_dimension_id = ?",
                Long.class, id);
        if (versions != null && versions > 0) {
            throw new IllegalStateException("已有发布历史的维度不能删除，请使用废弃流程");
        }
        mapper.deleteById(id);
    }

    public List<SemanticVersion> versions(Long id) {
        require(id);
        return jdbc.query("""
                SELECT id, version_no, snapshot_json, published_by, published_at
                FROM metadata.semantic_dimension_version
                WHERE semantic_dimension_id = ? ORDER BY version_no DESC
                """, this::mapVersion, id);
    }

    /** 发布时强校验真实字段；草稿保存只校验稳定引用，避免离线数据源阻断编辑。 */
    public Map<String, Object> validate(Long id) {
        SemanticDimension dimension = require(id);
        validateReferences(dimension, true);
        return Map.of("valid", true, "message", "维度绑定和真实字段校验通过");
    }

    private void validateReferences(SemanticDimension dimension, boolean liveColumnCheck) {
        if (dimension.getBusinessDomainId() != null) {
            if (liveColumnCheck) domainService.requireActive(dimension.getBusinessDomainId());
            else domainService.require(dimension.getBusinessDomainId());
        }
        MetadataTable table = tableService.getById(dimension.getMetadataTableId());
        if (table == null || "stale".equals(table.getStatus())) throw new IllegalArgumentException("绑定的数据表不存在或已失效");
        if (!liveColumnCheck) return;
        DataSource source = table.getDatasourceId() == null ? null : dataSourceService.getById(table.getDatasourceId());
        if (source == null) throw new IllegalArgumentException("绑定表所属数据源不存在");
        boolean exists = jdbcService.listColumns(source, table.getSchemaName(), table.getTableName()).stream()
                .map(DataSourceColumnDto::name)
                .anyMatch(name -> name.equalsIgnoreCase(dimension.getColumnName()));
        if (!exists) throw new IllegalArgumentException("维度字段 " + dimension.getColumnName() + " 在绑定表中不存在");
    }

    private void normalize(SemanticDimension dimension) {
        dimension.setDimensionCode(normalizeCode(dimension.getDimensionCode()));
        dimension.setDimensionName(dimension.getDimensionName().trim());
        dimension.setColumnName(dimension.getColumnName().trim());
        String type = StringUtils.hasText(dimension.getDimensionType())
                ? dimension.getDimensionType().trim().toLowerCase(Locale.ROOT) : "categorical";
        if (!TYPES.contains(type)) throw new IllegalArgumentException("不支持的维度类型");
        dimension.setDimensionType(type);
    }

    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("维度编码不能为空");
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private void ensureUnique(String code, Long excludeId) {
        long count = mapper.selectCount(new LambdaQueryWrapper<SemanticDimension>()
                .eq(SemanticDimension::getDimensionCode, code)
                .ne(excludeId != null, SemanticDimension::getId, excludeId));
        if (count > 0) throw new IllegalStateException("维度编码已存在：" + code);
    }

    private void requireDraft(SemanticDimension dimension) {
        if (!"draft".equals(dimension.getStatus())) {
            throw new IllegalStateException("已发布或已废弃版本不可直接修改，请先创建新版本");
        }
    }

    private void requireStableCode(String existing, String incoming) {
        if (!existing.equals(incoming)) throw new IllegalStateException("维度编码是稳定标识，创建后不能修改");
    }

    private Map<String, Object> snapshot(SemanticDimension dimension) {
        return Map.ofEntries(
                Map.entry("dimensionCode", dimension.getDimensionCode()),
                Map.entry("dimensionName", dimension.getDimensionName()),
                Map.entry("businessDomainId", dimension.getBusinessDomainId() == null ? 0L : dimension.getBusinessDomainId()),
                Map.entry("metadataTableId", dimension.getMetadataTableId()),
                Map.entry("columnName", dimension.getColumnName()),
                Map.entry("dimensionType", dimension.getDimensionType()),
                Map.entry("owner", dimension.getOwner() == null ? "" : dimension.getOwner()),
                Map.entry("description", dimension.getDescription() == null ? "" : dimension.getDescription()));
    }

    private SemanticVersion mapVersion(ResultSet rs, int row) throws SQLException {
        try {
            return new SemanticVersion(rs.getLong("id"), rs.getInt("version_no"),
                    objectMapper.readTree(rs.getString("snapshot_json")), rs.getString("published_by"),
                    rs.getObject("published_at", OffsetDateTime.class));
        } catch (JsonProcessingException error) {
            throw new SQLException("维度版本快照损坏", error);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法生成维度版本快照", error); }
    }

    private List<SemanticDimension> published(String keyword, Long tableId, Collection<Long> domainIds) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        return mapper.selectList(new LambdaQueryWrapper<SemanticDimension>()
                        .isNotNull(SemanticDimension::getPublishedVersion)
                        .ne(SemanticDimension::getStatus, "deprecated")
                        .orderByAsc(SemanticDimension::getDimensionCode)).stream()
                .map(this::resolvePublished)
                .filter(dimension -> tableId == null || tableId.equals(dimension.getMetadataTableId()))
                .filter(dimension -> domainIds == null || domainIds.contains(dimension.getBusinessDomainId()))
                .filter(dimension -> normalizedKeyword == null || Stream.of(dimension.getDimensionCode(), dimension.getDimensionName())
                        .filter(value -> value != null)
                        .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(normalizedKeyword)))
                .toList();
    }

    private SemanticDimension resolvePublished(SemanticDimension aggregate) {
        SemanticVersion active = jdbc.query("""
                SELECT id, version_no, snapshot_json, published_by, published_at
                FROM metadata.semantic_dimension_version
                WHERE semantic_dimension_id = ? AND version_no = ?
                """, this::mapVersion, aggregate.getId(), aggregate.getPublishedVersion()).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("维度在线版本快照缺失：" + aggregate.getDimensionCode()));
        JsonNode node = active.snapshot();
        SemanticDimension dimension = new SemanticDimension();
        dimension.setId(aggregate.getId());
        dimension.setDimensionCode(node.path("dimensionCode").asText(aggregate.getDimensionCode()));
        dimension.setDimensionName(node.path("dimensionName").asText(aggregate.getDimensionName()));
        dimension.setBusinessDomainId(nullableId(node.path("businessDomainId")));
        dimension.setMetadataTableId(nullableId(node.path("metadataTableId")));
        dimension.setColumnName(node.path("columnName").asText());
        dimension.setDimensionType(node.path("dimensionType").asText("categorical"));
        dimension.setOwner(nullableText(node.path("owner")));
        dimension.setDescription(nullableText(node.path("description")));
        dimension.setStatus("published");
        dimension.setVersion(active.versionNo());
        dimension.setPublishedVersion(active.versionNo());
        dimension.setCreatedBy(aggregate.getCreatedBy());
        dimension.setPublishedBy(active.publishedBy());
        dimension.setPublishedAt(active.publishedAt());
        dimension.setCreatedAt(aggregate.getCreatedAt());
        dimension.setUpdatedAt(aggregate.getUpdatedAt());
        return dimension;
    }

    private Long nullableId(JsonNode node) { long value = node.asLong(0); return value > 0 ? value : null; }
    private String nullableText(JsonNode node) { String value = node.asText(""); return value.isBlank() ? null : value; }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }
}
