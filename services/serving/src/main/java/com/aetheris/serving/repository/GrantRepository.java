package com.aetheris.serving.repository;

import com.aetheris.serving.dto.GrantInput;
import com.aetheris.serving.model.ServiceGrant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class GrantRepository {

    private final JdbcTemplate jdbc;

    public GrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ServiceGrant> list(Optional<String> status) {
        return status.filter(value -> !value.isBlank())
                .map(value -> jdbc.query(
                        "SELECT * FROM serving.service_grant WHERE status = ? ORDER BY updated_at DESC",
                        this::map, value.trim()))
                .orElseGet(() -> jdbc.query(
                        "SELECT * FROM serving.service_grant ORDER BY updated_at DESC", this::map));
    }

    public Optional<ServiceGrant> find(Long id) {
        return jdbc.query("SELECT * FROM serving.service_grant WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    public ServiceGrant create(GrantInput input, String username) {
        Long id = jdbc.queryForObject("""
                INSERT INTO serving.service_grant
                    (api_path, grantee, grant_type, scope, expires_at, status, granted_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                input.apiPath().trim(), input.grantee().trim(), orDefault(input.grantType(), "user"),
                orDefault(input.scope(), "read"), input.expiresAt(), orDefault(input.status(), "active"), username);
        return find(id).orElseThrow();
    }

    public Optional<ServiceGrant> update(Long id, GrantInput input) {
        int changed = jdbc.update("""
                UPDATE serving.service_grant
                   SET api_path = ?, grantee = ?, grant_type = ?, scope = ?, expires_at = ?, status = ?, updated_at = now()
                 WHERE id = ?
                """,
                input.apiPath().trim(), input.grantee().trim(), orDefault(input.grantType(), "user"),
                orDefault(input.scope(), "read"), input.expiresAt(), orDefault(input.status(), "active"), id);
        return changed == 0 ? Optional.empty() : find(id);
    }

    public Optional<ServiceGrant> updateStatus(Long id, String status) {
        int changed = jdbc.update(
                "UPDATE serving.service_grant SET status = ?, updated_at = now() WHERE id = ?", status, id);
        return changed == 0 ? Optional.empty() : find(id);
    }

    public boolean delete(Long id) {
        return jdbc.update("DELETE FROM serving.service_grant WHERE id = ?", id) > 0;
    }

    private ServiceGrant map(ResultSet rs, int row) throws SQLException {
        return new ServiceGrant(
                rs.getLong("id"), rs.getString("api_path"), rs.getString("grantee"), rs.getString("grant_type"),
                rs.getString("scope"), rs.getObject("expires_at", java.time.OffsetDateTime.class),
                rs.getString("status"), rs.getString("granted_by"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
