package com.aetheris.mcp.tasks;

import com.aetheris.mcp.support.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class McpTaskRepository {

    private final JdbcTemplate jdbc;

    public McpTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long uid, String username, String type, String status, Long approvalId, String labCode,
                       String payloadJson) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mcp.mcp_task (uid, username, type, status, approval_id, lab_code, payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, new String[]{"id"});
            statement.setLong(1, uid);
            statement.setString(2, username);
            statement.setString(3, type);
            statement.setString(4, status);
            if (approvalId == null) statement.setNull(5, java.sql.Types.BIGINT);
            else statement.setLong(5, approvalId);
            statement.setString(6, labCode);
            statement.setString(7, payloadJson);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("任务写入失败");
        return key.longValue();
    }

    public Optional<McpTask> find(long id, long uid) {
        return jdbc.query("""
                SELECT id, uid, username, type, status, approval_id, lab_code, payload::text AS payload,
                       result::text AS result, error, created_at, updated_at
                  FROM mcp.mcp_task WHERE id = ? AND uid = ?
                """, mapper(), id, uid).stream().findFirst();
    }

    public Optional<McpTask> findByApproval(long approvalId, long uid) {
        return jdbc.query("""
                SELECT id, uid, username, type, status, approval_id, lab_code, payload::text AS payload,
                       result::text AS result, error, created_at, updated_at
                  FROM mcp.mcp_task WHERE approval_id = ? AND uid = ?
                 ORDER BY id DESC LIMIT 1
                """, mapper(), approvalId, uid).stream().findFirst();
    }

    public List<McpTask> list(long uid, int limit) {
        return jdbc.query("""
                SELECT id, uid, username, type, status, approval_id, lab_code, payload::text AS payload,
                       result::text AS result, error, created_at, updated_at
                  FROM mcp.mcp_task WHERE uid = ? ORDER BY created_at DESC LIMIT ?
                """, mapper(), uid, limit);
    }

    public void update(long id, String status, String resultJson, String error) {
        jdbc.update("""
                UPDATE mcp.mcp_task SET status = ?, result = ?::jsonb, error = ?, updated_at = now() WHERE id = ?
                """, status, resultJson == null ? "{}" : resultJson, error, id);
    }

    private static RowMapper<McpTask> mapper() {
        return (rs, index) -> new McpTask(rs.getLong("id"), rs.getLong("uid"), rs.getString("username"),
                rs.getString("type"), rs.getString("status"), nullableLong(rs, "approval_id"),
                rs.getString("lab_code"), Json.readTree(rs.getString("payload")),
                Json.readTree(rs.getString("result")), rs.getString("error"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
