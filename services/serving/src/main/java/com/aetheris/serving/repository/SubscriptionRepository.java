package com.aetheris.serving.repository;

import com.aetheris.serving.dto.SubscriptionInput;
import com.aetheris.serving.model.DataSubscription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class SubscriptionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SubscriptionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<DataSubscription> list(Optional<String> status) {
        return status.filter(value -> !value.isBlank())
                .map(value -> jdbc.query(
                        "SELECT * FROM serving.data_subscription WHERE status = ? ORDER BY updated_at DESC",
                        this::map, value.trim()))
                .orElseGet(() -> jdbc.query(
                        "SELECT * FROM serving.data_subscription ORDER BY updated_at DESC", this::map));
    }

    public Optional<DataSubscription> find(Long id) {
        return jdbc.query("SELECT * FROM serving.data_subscription WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    public DataSubscription create(SubscriptionInput input, String username) {
        Long id = jdbc.queryForObject("""
                INSERT INTO serving.data_subscription
                    (subscription_name, subscriber, target_type, target_name, deliver_type, frequency, config_json, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                RETURNING id
                """, Long.class,
                input.name().trim(), input.subscriber().trim(), orDefault(input.targetType(), "table"),
                input.targetName().trim(), orDefault(input.deliverType(), "api"), clean(input.frequency()),
                json(input.config()), orDefault(input.status(), "active"), username);
        return find(id).orElseThrow();
    }

    public Optional<DataSubscription> update(Long id, SubscriptionInput input) {
        int changed = jdbc.update("""
                UPDATE serving.data_subscription
                   SET subscription_name = ?, subscriber = ?, target_type = ?, target_name = ?,
                       deliver_type = ?, frequency = ?, config_json = ?::jsonb, status = ?, updated_at = now()
                 WHERE id = ?
                """,
                input.name().trim(), input.subscriber().trim(), orDefault(input.targetType(), "table"),
                input.targetName().trim(), orDefault(input.deliverType(), "api"), clean(input.frequency()),
                json(input.config()), orDefault(input.status(), "active"), id);
        return changed == 0 ? Optional.empty() : find(id);
    }

    public boolean delete(Long id) {
        return jdbc.update("DELETE FROM serving.data_subscription WHERE id = ?", id) > 0;
    }

    private DataSubscription map(ResultSet rs, int row) throws SQLException {
        return new DataSubscription(
                rs.getLong("id"), rs.getString("subscription_name"), rs.getString("subscriber"),
                rs.getString("target_type"), rs.getString("target_name"), rs.getString("deliver_type"),
                rs.getString("frequency"), parse(rs.getString("config_json")), rs.getString("status"),
                rs.getString("created_by"), rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("订阅配置损坏", error);
        }
    }

    private String json(JsonNode value) {
        if (value == null) return "{}";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("无法保存订阅配置", error);
        }
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
