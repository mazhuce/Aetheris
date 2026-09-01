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
        return repository.createDataset(input, username);
    }

    public AnalyticsDataset updateDataset(Long id, DatasetInput input) {
        queryCache.invalidate(id);
        return repository.updateDataset(id, input).orElseThrow(() -> new NotFoundException("数据集不存在"));
    }

    public void deleteDataset(Long id) {
        queryCache.invalidate(id);
        if (!repository.deleteDataset(id)) throw new NotFoundException("数据集不存在");
    }

    public Map<String, Object> queryDataset(Long id, boolean refresh) {
        AnalyticsDataset dataset = dataset(id);
        if (refresh) queryCache.invalidate(id);
        return queryCache.get(id, ignored -> {
            Map<String, Object> result = queryClient.query(dataset.datasourceId(), dataset.querySql());
            Object columns = result.get("columns");
            JsonNode schema = objectMapper.valueToTree(Map.of("columns", columns == null ? List.of() : columns));
            repository.updateDatasetSchema(id, schema);
            return result;
        });
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
        return repository.publishDashboard(id, username).orElseThrow(() -> new NotFoundException("看板不存在"));
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
}
