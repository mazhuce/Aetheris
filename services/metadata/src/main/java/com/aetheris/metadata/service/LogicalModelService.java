package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.LogicalModel;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.mapper.LogicalModelMapper;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 逻辑模型聚合根：维护实体图、严格生命周期和不可变发布快照。 */
@Service
public class LogicalModelService {
    private static final List<String> MODEL_TYPES = List.of("conceptual", "relational", "dimensional");
    private static final List<String> ENTITY_ROLES = List.of("entity", "fact", "dimension", "bridge");
    private static final List<String> CARDINALITIES = List.of("1:1", "1:N", "N:1", "N:N");
    private final LogicalModelMapper mapper;
    private final MetadataTableService tableService;
    private final BusinessDomainService domainService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public LogicalModelService(LogicalModelMapper mapper, MetadataTableService tableService,
                               BusinessDomainService domainService, ObjectMapper objectMapper,
                               JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.tableService = tableService;
        this.domainService = domainService;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    public List<LogicalModel> list(String keyword, String status) {
        return list(keyword, status, null);
    }

    /**
     * @param domainIds 业务域 id 白名单（权限收窄列表过滤）；null 表示不过滤，
     *                  空集合时返回空列表。
     */
    public List<LogicalModel> list(String keyword, String status, Collection<Long> domainIds) {
        if (domainIds != null && domainIds.isEmpty()) {
            return List.of();
        }
        if ("published".equals(status)) return published(keyword, domainIds);
        LambdaQueryWrapper<LogicalModel> wrapper = new LambdaQueryWrapper<LogicalModel>()
                .eq(StringUtils.hasText(status), LogicalModel::getStatus, status)
                .in(domainIds != null, LogicalModel::getBusinessDomainId, domainIds)
                .orderByDesc(LogicalModel::getUpdatedAt);
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(LogicalModel::getModelName, trimmed)
                    .or().like(LogicalModel::getModelCode, trimmed)
                    .or().like(LogicalModel::getBusinessDomain, trimmed));
        }
        return mapper.selectList(wrapper);
    }

    public List<LogicalModel> list(String keyword) { return list(keyword, null); }

    private List<LogicalModel> published(String keyword, Collection<Long> domainIds) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : null;
        return mapper.selectList(new LambdaQueryWrapper<LogicalModel>()
                        .isNotNull(LogicalModel::getPublishedVersion)
                        .ne(LogicalModel::getStatus, "deprecated")
                        .orderByAsc(LogicalModel::getModelCode)).stream()
                .map(this::resolvePublished)
                .filter(model -> domainIds == null || domainIds.contains(model.getBusinessDomainId()))
                .filter(model -> normalizedKeyword == null || Stream.of(model.getModelCode(), model.getModelName(), model.getBusinessDomain())
                        .filter(value -> value != null)
                        .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(normalizedKeyword)))
                .toList();
    }

    public LogicalModel getById(Long id) { return mapper.selectById(id); }
    public LogicalModel require(Long id) {
        LogicalModel model = getById(id);
        if (model == null) throw new NotFoundException("逻辑模型不存在");
        return model;
    }

    public LogicalModel requirePublished(Long id) {
        LogicalModel model = require(id);
        if (model.getPublishedVersion() == null || "deprecated".equals(model.getStatus())) {
            throw new IllegalArgumentException("绑定的逻辑模型尚未发布");
        }
        return resolvePublished(model);
    }

    public boolean codeExists(String code, Long excludeId) {
        return mapper.selectCount(new LambdaQueryWrapper<LogicalModel>()
                .eq(LogicalModel::getModelCode, normalizeCode(code))
                .ne(excludeId != null, LogicalModel::getId, excludeId)) > 0;
    }

    @Transactional
    public LogicalModel create(LogicalModel model, String username) {
        normalize(model);
        if (codeExists(model.getModelCode(), null)) throw new IllegalStateException("模型编码已存在：" + model.getModelCode());
        validateReferences(model, false);
        model.setId(null);
        model.setStatus("draft");
        model.setVersion(1);
        model.setPublishedVersion(null);
        model.setCreatedBy(username);
        model.setPublishedBy(null);
        model.setPublishedAt(null);
        mapper.insert(model);
        return model;
    }

    @Transactional
    public LogicalModel update(Long id, LogicalModel input) {
        LogicalModel existing = require(id);
        requireDraft(existing);
        normalize(input);
        requireStableCode(existing.getModelCode(), input.getModelCode());
        if (codeExists(input.getModelCode(), id)) throw new IllegalStateException("模型编码已存在：" + input.getModelCode());
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

    public Map<String, Object> validate(Long id) {
        LogicalModel model = require(id);
        ModelStats stats = validateReferences(model, true);
        return Map.of("valid", true, "entities", stats.entities(), "relations", stats.relations(),
                "message", "模型实体、数据表绑定和关系校验通过");
    }

    @Transactional
    public LogicalModel publish(Long id, String username) {
        LogicalModel model = require(id);
        requireDraft(model);
        validateReferences(model, true);
        OffsetDateTime now = OffsetDateTime.now();
        model.setStatus("published");
        model.setPublishedVersion(model.getVersion());
        model.setPublishedBy(username);
        model.setPublishedAt(now);
        model.setUpdatedAt(now);
        mapper.updateById(model);
        jdbc.update("""
                INSERT INTO metadata.logical_model_version
                    (logical_model_id, version_no, snapshot_json, published_by, published_at)
                VALUES (?, ?, ?::jsonb, ?, ?)
                """, id, model.getVersion(), snapshot(model), username, now);
        return require(id);
    }

    @Transactional
    public LogicalModel newVersion(Long id) {
        LogicalModel model = require(id);
        if (!List.of("published", "deprecated").contains(model.getStatus())) {
            throw new IllegalStateException("只有已发布或已废弃模型可以创建新版本");
        }
        model.setVersion(model.getVersion() + 1);
        model.setStatus("draft");
        model.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(model);
        return require(id);
    }

    @Transactional
    public LogicalModel discardDraft(Long id) {
        LogicalModel current = require(id);
        requireDraft(current);
        if (current.getPublishedVersion() == null) throw new IllegalStateException("首个未发布草稿不能回退，可直接删除");
        LogicalModel published = resolvePublished(current);
        published.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(published);
        return require(id);
    }

    @Transactional
    public LogicalModel deprecate(Long id) {
        LogicalModel model = require(id);
        if (model.getPublishedVersion() == null) throw new IllegalStateException("只有存在在线版本的模型可以废弃");
        model.setStatus("deprecated");
        model.setPublishedVersion(null);
        model.setUpdatedAt(OffsetDateTime.now());
        mapper.updateById(model);
        return require(id);
    }

    @Transactional
    public void remove(Long id) {
        LogicalModel model = require(id);
        requireDraft(model);
        Long versions = jdbc.queryForObject("SELECT count(*) FROM metadata.logical_model_version WHERE logical_model_id = ?", Long.class, id);
        if (versions != null && versions > 0) throw new IllegalStateException("已有发布历史的模型不能删除，请使用废弃流程");
        mapper.deleteById(id);
    }

    public List<SemanticVersion> versions(Long id) {
        require(id);
        return jdbc.query("""
                SELECT id, version_no, snapshot_json, published_by, published_at
                FROM metadata.logical_model_version WHERE logical_model_id = ? ORDER BY version_no DESC
                """, this::mapVersion, id);
    }

    private void normalize(LogicalModel model) {
        model.setModelCode(normalizeCode(model.getModelCode()));
        model.setModelName(model.getModelName().trim());
        String type = StringUtils.hasText(model.getModelType()) ? model.getModelType().trim().toLowerCase(Locale.ROOT) : "relational";
        if (!MODEL_TYPES.contains(type)) throw new IllegalArgumentException("不支持的模型类型");
        model.setModelType(type);
        if (!StringUtils.hasText(model.getEntitiesJson())) model.setEntitiesJson("{\"entities\":[],\"relations\":[]}");
        if (model.getBusinessDomainId() != null) {
            var domain = domainService.require(model.getBusinessDomainId());
            model.setBusinessDomain(domain.getDomainName());
        }
    }

    private ModelStats validateReferences(LogicalModel model, boolean publishMode) {
        if (model.getBusinessDomainId() != null) {
            if (publishMode) domainService.requireActive(model.getBusinessDomainId());
            else domainService.require(model.getBusinessDomainId());
        }
        JsonNode root = parse(model.getEntitiesJson());
        JsonNode entities = root.path("entities");
        JsonNode relations = root.path("relations");
        if (!entities.isArray() || !relations.isArray()) throw new IllegalArgumentException("模型结构必须包含 entities 和 relations 数组");
        if (publishMode && entities.isEmpty()) throw new IllegalArgumentException("发布模型至少需要一个实体");
        Set<String> codes = new HashSet<>();
        for (JsonNode entity : entities) {
            String code = text(entity, "code").toUpperCase(Locale.ROOT);
            String name = text(entity, "name");
            String role = entity.path("role").asText("entity");
            if (code.isBlank() || !code.matches("[A-Z][A-Z0-9_]{1,63}")) throw new IllegalArgumentException("实体编码格式不正确");
            if (name.isBlank()) throw new IllegalArgumentException("实体名称不能为空");
            if (!codes.add(code)) throw new IllegalArgumentException("实体编码重复：" + code);
            if (!ENTITY_ROLES.contains(role)) throw new IllegalArgumentException("实体角色不支持：" + role);
            if (entity.hasNonNull("tableId")) {
                MetadataTable table = tableService.getById(entity.get("tableId").asLong());
                if (table == null || "stale".equals(table.getStatus())) throw new IllegalArgumentException("实体 " + name + " 绑定的数据表不存在或已失效");
            } else if (publishMode && !"conceptual".equals(model.getModelType())) {
                throw new IllegalArgumentException("关系/维度模型中的实体 " + name + " 必须绑定数据表");
            }
        }
        for (JsonNode relation : relations) {
            String source = text(relation, "source").toUpperCase(Locale.ROOT);
            String target = text(relation, "target").toUpperCase(Locale.ROOT);
            String cardinality = relation.path("cardinality").asText("N:1");
            String join = relation.path("joinExpression").asText("");
            if (!codes.contains(source) || !codes.contains(target)) throw new IllegalArgumentException("关系引用了不存在的实体");
            if (source.equals(target)) throw new IllegalArgumentException("关系不能连接实体自身");
            if (!CARDINALITIES.contains(cardinality)) throw new IllegalArgumentException("不支持的关系基数：" + cardinality);
            if (publishMode && !"conceptual".equals(model.getModelType()) && join.isBlank()) {
                throw new IllegalArgumentException("关系 " + source + " → " + target + " 缺少关联表达式");
            }
            if (!join.isBlank() && !join.matches("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("关联表达式仅允许字段等值关系");
            }
        }
        return new ModelStats(entities.size(), relations.size());
    }

    private JsonNode parse(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("模型结构不是有效 JSON"); }
    }
    private String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("模型编码不能为空");
        return code.trim().toUpperCase(Locale.ROOT);
    }
    private void requireDraft(LogicalModel model) {
        if (!"draft".equals(model.getStatus())) throw new IllegalStateException("已发布或已废弃版本不可直接修改，请先创建新版本");
    }
    private void requireStableCode(String existing, String incoming) {
        if (!existing.equals(incoming)) throw new IllegalStateException("模型编码是稳定标识，创建后不能修改");
    }
    private String snapshot(LogicalModel model) {
        try {
            return objectMapper.writeValueAsString(Map.ofEntries(
                    Map.entry("modelCode", model.getModelCode()), Map.entry("modelName", model.getModelName()),
                    Map.entry("businessDomainId", model.getBusinessDomainId() == null ? 0L : model.getBusinessDomainId()),
                    Map.entry("businessDomain", model.getBusinessDomain() == null ? "" : model.getBusinessDomain()),
                    Map.entry("modelType", model.getModelType()), Map.entry("graph", parse(model.getEntitiesJson())),
                    Map.entry("description", model.getDescription() == null ? "" : model.getDescription()),
                    Map.entry("owner", model.getOwner() == null ? "" : model.getOwner())));
        } catch (JsonProcessingException error) { throw new IllegalStateException("无法生成模型版本快照", error); }
    }
    private SemanticVersion mapVersion(ResultSet rs, int row) throws SQLException {
        try {
            return new SemanticVersion(rs.getLong("id"), rs.getInt("version_no"), objectMapper.readTree(rs.getString("snapshot_json")),
                    rs.getString("published_by"), rs.getObject("published_at", OffsetDateTime.class));
        } catch (JsonProcessingException error) { throw new SQLException("模型版本快照损坏", error); }
    }

    private LogicalModel resolvePublished(LogicalModel aggregate) {
        SemanticVersion active = jdbc.query("""
                SELECT id, version_no, snapshot_json, published_by, published_at
                FROM metadata.logical_model_version
                WHERE logical_model_id = ? AND version_no = ?
                """, this::mapVersion, aggregate.getId(), aggregate.getPublishedVersion()).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("逻辑模型在线版本快照缺失：" + aggregate.getModelCode()));
        JsonNode node = active.snapshot();
        LogicalModel model = new LogicalModel();
        model.setId(aggregate.getId());
        model.setModelCode(node.path("modelCode").asText(aggregate.getModelCode()));
        model.setModelName(node.path("modelName").asText(aggregate.getModelName()));
        model.setBusinessDomainId(nullableId(node.path("businessDomainId")));
        model.setBusinessDomain(node.path("businessDomain").asText(aggregate.getBusinessDomain()));
        model.setModelType(node.path("modelType").asText("relational"));
        model.setEntitiesJson(node.path("graph").toString());
        model.setDescription(nullableText(node.path("description")));
        model.setOwner(nullableText(node.path("owner")));
        model.setStatus("published");
        model.setVersion(active.versionNo());
        model.setPublishedVersion(active.versionNo());
        model.setCreatedBy(aggregate.getCreatedBy());
        model.setPublishedBy(active.publishedBy());
        model.setPublishedAt(active.publishedAt());
        model.setCreatedAt(aggregate.getCreatedAt());
        model.setUpdatedAt(aggregate.getUpdatedAt());
        return model;
    }
    private Long nullableId(JsonNode node) { long value = node.asLong(0); return value > 0 ? value : null; }
    private String nullableText(JsonNode node) { String value = node.asText(""); return value.isBlank() ? null : value; }

    private record ModelStats(int entities, int relations) { }
    public static class NotFoundException extends RuntimeException { public NotFoundException(String message) { super(message); } }
}
