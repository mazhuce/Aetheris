package com.aetheris.serving.repository;

import com.aetheris.serving.dto.DatasetInput;
import com.aetheris.serving.model.AnalyticsDataset;
import com.aetheris.serving.model.Dashboard;
import com.aetheris.serving.model.DashboardTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class AnalyticsRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AnalyticsRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<AnalyticsDataset> listDatasets() {
        return jdbc.query("SELECT * FROM serving.bi_dataset ORDER BY updated_at DESC", this::mapDataset);
    }

    public Optional<AnalyticsDataset> findDataset(Long id) {
        return jdbc.query("SELECT * FROM serving.bi_dataset WHERE id = ?", this::mapDataset, id).stream().findFirst();
    }

    public AnalyticsDataset createDataset(DatasetInput input, String username) {
        Long id = jdbc.queryForObject("""
                INSERT INTO serving.bi_dataset
                    (name, description, source_type, datasource_id, datasource_name, query_sql,
                     metric_code, metric_query_json, refresh_seconds, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                RETURNING id
                """, Long.class,
                input.name().trim(), clean(input.description()), sourceType(input), sqlDatasourceId(input), sqlValue(input.datasourceName(), input),
                sqlValue(input.querySql(), input), metricCode(input), metricQuery(input),
                input.refreshSeconds() == null ? 30 : input.refreshSeconds(), username);
        return findDataset(id).orElseThrow();
    }

    public Optional<AnalyticsDataset> updateDataset(Long id, DatasetInput input) {
        int changed = jdbc.update("""
                UPDATE serving.bi_dataset
                   SET name = ?, description = ?, source_type = ?, datasource_id = ?, datasource_name = ?, query_sql = ?,
                       metric_code = ?, metric_query_json = ?::jsonb, refresh_seconds = ?,
                       schema_json = '{}'::jsonb, updated_at = now()
                 WHERE id = ?
                """,
                input.name().trim(), clean(input.description()), sourceType(input), sqlDatasourceId(input), sqlValue(input.datasourceName(), input),
                sqlValue(input.querySql(), input), metricCode(input), metricQuery(input),
                input.refreshSeconds() == null ? 30 : input.refreshSeconds(), id);
        return changed == 0 ? Optional.empty() : findDataset(id);
    }

    public boolean deleteDataset(Long id) {
        return jdbc.update("DELETE FROM serving.bi_dataset WHERE id = ?", id) > 0;
    }

    public void updateDatasetSchema(Long id, JsonNode schema) {
        jdbc.update("UPDATE serving.bi_dataset SET schema_json = ?::jsonb, updated_at = now() WHERE id = ?",
                json(schema), id);
    }

    public List<DashboardTemplate> listTemplates() {
        return jdbc.query("SELECT * FROM serving.bi_dashboard_template WHERE enabled = true ORDER BY sort_order, id", this::mapTemplate);
    }

    public Optional<DashboardTemplate> findTemplate(Long id) {
        return jdbc.query("SELECT * FROM serving.bi_dashboard_template WHERE id = ? AND enabled = true", this::mapTemplate, id)
                .stream().findFirst();
    }

    public List<Dashboard> listDashboards() {
        return jdbc.query("SELECT * FROM serving.bi_dashboard ORDER BY updated_at DESC", this::mapDashboard);
    }

    public Optional<Dashboard> findDashboard(Long id) {
        return jdbc.query("SELECT * FROM serving.bi_dashboard WHERE id = ?", this::mapDashboard, id).stream().findFirst();
    }

    public Optional<Dashboard> findPublishedDashboard(String slug) {
        return jdbc.query("SELECT * FROM serving.bi_dashboard WHERE slug = ? AND status = 'published'", this::mapDashboard, slug)
                .stream().findFirst();
    }

    public Dashboard createDashboard(String name, String description, String slug, JsonNode spec, String username) {
        Long id = jdbc.queryForObject("""
                INSERT INTO serving.bi_dashboard
                    (name, description, slug, draft_spec, created_by, updated_by)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                RETURNING id
                """, Long.class, name.trim(), clean(description), slug, json(spec), username, username);
        return findDashboard(id).orElseThrow();
    }

    public Optional<Dashboard> updateDashboard(Long id, String name, String description, JsonNode spec, String username) {
        int changed = jdbc.update("""
                UPDATE serving.bi_dashboard
                   SET name = ?, description = ?, draft_spec = ?::jsonb, updated_by = ?, updated_at = now()
                 WHERE id = ?
                """, name.trim(), clean(description), json(spec), username, id);
        return changed == 0 ? Optional.empty() : findDashboard(id);
    }

    @Transactional
    public Optional<Dashboard> publishDashboard(Long id, String username) {
        // Serialize version allocation inside the transaction; otherwise two
        // concurrent publishers can both calculate the same next version.
        if (jdbc.query("SELECT id FROM serving.bi_dashboard WHERE id = ? FOR UPDATE", (rs, row) -> rs.getLong("id"), id).isEmpty()) {
            return Optional.empty();
        }
        Optional<Dashboard> existing = findDashboard(id);
        if (existing.isEmpty()) return Optional.empty();
        Dashboard dashboard = existing.get();
        int version = dashboard.publishedVersion() + 1;
        jdbc.update("""
                INSERT INTO serving.bi_dashboard_version (dashboard_id, version_no, spec_json, created_by)
                VALUES (?, ?, ?::jsonb, ?)
                """, id, version, json(dashboard.draftSpec()), username);
        jdbc.update("""
                UPDATE serving.bi_dashboard
                   SET published_spec = draft_spec, published_version = ?, status = 'published',
                       published_at = now(), updated_by = ?, updated_at = now()
                 WHERE id = ?
                """, version, username, id);
        return findDashboard(id);
    }

    public boolean deleteDashboard(Long id) {
        return jdbc.update("DELETE FROM serving.bi_dashboard WHERE id = ?", id) > 0;
    }

    private AnalyticsDataset mapDataset(ResultSet rs, int row) throws SQLException {
        return new AnalyticsDataset(
                rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("source_type"), nullableLong(rs, "datasource_id"), rs.getString("datasource_name"), rs.getString("query_sql"),
                rs.getString("metric_code"), parse(rs.getString("metric_query_json")),
                parse(rs.getString("schema_json")), rs.getInt("refresh_seconds"), rs.getString("status"),
                rs.getString("created_by"), rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private Dashboard mapDashboard(ResultSet rs, int row) throws SQLException {
        return new Dashboard(
                rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getString("slug"),
                rs.getString("status"), parse(rs.getString("draft_spec")), parse(rs.getString("published_spec")),
                rs.getInt("published_version"), rs.getString("created_by"), rs.getString("updated_by"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class),
                rs.getObject("published_at", java.time.OffsetDateTime.class));
    }

    private DashboardTemplate mapTemplate(ResultSet rs, int row) throws SQLException {
        return new DashboardTemplate(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("category"), rs.getString("preview_tone"), parse(rs.getString("spec_json")),
                rs.getInt("sort_order"));
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("看板配置损坏", error);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("无法保存看板配置", error);
        }
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sourceType(DatasetInput input) {
        return input.sourceType() == null || input.sourceType().isBlank() ? "sql" : input.sourceType().trim().toLowerCase(java.util.Locale.ROOT);
    }

    private Long sqlDatasourceId(DatasetInput input) { return "sql".equals(sourceType(input)) ? input.datasourceId() : null; }
    private String sqlValue(String value, DatasetInput input) { return "sql".equals(sourceType(input)) ? clean(value) : null; }
    private String metricCode(DatasetInput input) {
        String value = "metric".equals(sourceType(input)) ? clean(input.metricCode()) : null;
        return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
    }
    private String metricQuery(DatasetInput input) {
        return "metric".equals(sourceType(input)) && input.metricQuery() != null ? json(input.metricQuery()) : "{}";
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
