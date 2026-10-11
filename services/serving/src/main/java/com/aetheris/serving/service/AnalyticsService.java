package com.aetheris.serving.service;

import com.aetheris.serving.dto.DashboardCreateRequest;
import com.aetheris.serving.dto.DashboardUpdateRequest;
import com.aetheris.serving.dto.DatasetInput;
import com.aetheris.serving.model.AnalyticsDataset;
import com.aetheris.serving.model.Dashboard;
import com.aetheris.serving.repository.AnalyticsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnalyticsService {

    private final AnalyticsRepository repository;
    private final MetadataQueryClient queryClient;
    private final ObjectMapper objectMapper;
    private final Cache<Long, Map<String, Object>> queryCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofSeconds(15))
            .build();

    public AnalyticsService(AnalyticsRepository repository, MetadataQueryClient queryClient, ObjectMapper objectMapper) {
        this.repository = repository;
        this.queryClient = queryClient;
        this.objectMapper = objectMapper;
    }

    public List<AnalyticsDataset> datasets() { return repository.listDatasets(); }

    public AnalyticsDataset dataset(Long id) {
        return repository.findDataset(id).orElseThrow(() -> new NotFoundException("数据集不存在"));
    }

    public AnalyticsDataset createDataset(DatasetInput input, String username) {
        validateDataset(input);
        return repository.createDataset(input, username);
    }

    public AnalyticsDataset updateDataset(Long id, DatasetInput input) {
        validateDataset(input);
        queryCache.invalidate(id);
        return repository.updateDataset(id, input).orElseThrow(() -> new NotFoundException("数据集不存在"));
    }

    public AnalyticsDataset publishDataset(Long id, String username) {
        queryCache.invalidate(id);
        return repository.publishDataset(id, username)
                .orElseThrow(() -> new NotFoundException("数据集不存在或已废弃"));
    }

    public void deleteDataset(Long id) {
        queryCache.invalidate(id);
        if (!repository.deleteDataset(id)) throw new NotFoundException("数据集不存在");
    }

    public Map<String, Object> queryDataset(Long id, boolean refresh) {
        return queryDataset(id, refresh, null);
    }

    public Map<String, Object> queryDataset(Long id, boolean refresh, String identityHeader) {
        AnalyticsDataset dataset = dataset(id);
        if (!isPublished(dataset)) {
            throw new DatasetNotPublishedException();
        }
        if (refresh) queryCache.invalidate(id);
        // 用户查询必须在每次请求中走 metadata 的权限/审计链路。共享结果缓存
        // 只保留给没有请求上下文的内部调用，避免把一个用户的行级结果复用给另一个用户。
        if (identityHeader != null && !identityHeader.isBlank()) {
            return executeQuery(id, dataset, identityHeader);
        }
        return queryCache.get(id, ignored -> executeQuery(id, dataset, null));
    }

    private Map<String, Object> executeQuery(Long id, AnalyticsDataset dataset, String identityHeader) {
            Map<String, Object> result = "metric".equals(dataset.sourceType())
                    ? (identityHeader == null
                        ? queryClient.queryMetric(dataset.metricCode(), dataset.metricQuery())
                        : queryClient.queryMetric(dataset.metricCode(), dataset.metricQuery(), identityHeader))
                    : (identityHeader == null
                        ? queryClient.query(dataset.datasourceId(), dataset.querySql())
                        : queryClient.query(dataset.datasourceId(), dataset.querySql(), identityHeader));
            Object columns = result.get("columns");
            JsonNode schema = objectMapper.valueToTree(Map.of("columns", columns == null ? List.of() : columns));
            repository.updateDatasetSchema(id, schema);
            return result;
    }

    private boolean isPublished(AnalyticsDataset dataset) {
        return "published".equalsIgnoreCase(dataset.status());
    }

    private void validateDataset(DatasetInput input) {
        String sourceType = input.sourceType() == null || input.sourceType().isBlank()
                ? "sql" : input.sourceType().trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("sql", "metric").contains(sourceType)) throw new IllegalArgumentException("数据集来源仅支持 sql 或 metric");
        if ("sql".equals(sourceType) && (input.datasourceId() == null || input.querySql() == null || input.querySql().isBlank())) {
            throw new IllegalArgumentException("SQL 数据集必须选择数据源并填写只读 SQL");
        }
        if ("metric".equals(sourceType) && (input.metricCode() == null || input.metricCode().isBlank())) {
            throw new IllegalArgumentException("指标数据集必须选择已发布指标");
        }
    }

    public Dashboard createDashboard(DashboardCreateRequest request, String username) {
        JsonNode spec = request.templateId() == null
                ? blankSpec()
                : repository.findTemplate(request.templateId())
                    .orElseThrow(() -> new NotFoundException("模板不存在"))
                    .spec();
        String slug = "dash-" + UUID.randomUUID().toString().substring(0, 8);
        return repository.createDashboard(request.name(), request.description(), slug, spec, username);
    }

    public Dashboard updateDashboard(Long id, DashboardUpdateRequest request, String username) {
        return repository.updateDashboard(id, request.name(), request.description(), request.spec(), username)
                .orElseThrow(() -> new NotFoundException("看板不存在"));
    }

    public Dashboard dashboard(Long id) {
        return repository.findDashboard(id).orElseThrow(() -> new NotFoundException("看板不存在"));
    }

    public Dashboard published(String slug) {
        return repository.findPublishedDashboard(slug).orElseThrow(() -> new NotFoundException("看板未发布或不存在"));
    }

    public Dashboard publish(Long id, String username) {
        try {
            return repository.publishDashboard(id, username)
                    .orElseThrow(() -> new NotFoundException("看板不存在"));
        } catch (AnalyticsRepository.PublishConflictException error) {
            throw new PublishConflictException(error.getMessage());
        }
    }

    public void deleteDashboard(Long id) {
        if (!repository.deleteDashboard(id)) throw new NotFoundException("看板不存在");
    }

    private JsonNode blankSpec() {
        ObjectNode spec = objectMapper.createObjectNode();
        ObjectNode layouts = objectMapper.createObjectNode();
        layouts.set("lg", objectMapper.createArrayNode());
        spec.put("schemaVersion", 1);
        spec.put("theme", "dark");
        spec.set("layouts", layouts);
        spec.set("widgets", objectMapper.createArrayNode());
        spec.set("globalFilters", objectMapper.createArrayNode());
        return spec;
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class PublishConflictException extends RuntimeException {
        public PublishConflictException(String message) { super(message); }
    }

    public static class DatasetNotPublishedException extends RuntimeException {
        public DatasetNotPublishedException() { super("数据集尚未发布，不能执行查询"); }
    }
}
