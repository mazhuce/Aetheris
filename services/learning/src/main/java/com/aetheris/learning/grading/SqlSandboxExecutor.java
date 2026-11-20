package com.aetheris.learning.grading;

import com.aetheris.learning.config.LearningProperties;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 在学员沙箱中执行只读查询。连接级只读事务 + 语句超时 + search_path 锁定，
 * 任何写语句都会在数据库侧再次被拒绝（静态守卫是第一道，不是唯一一道）。
 */
@Component
public class SqlSandboxExecutor {

    private static final Pattern SAFE_SCHEMA = Pattern.compile("^sb_u[0-9]{1,18}$");
    private static final Pattern SAFE_ROLE = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final Pattern SAFE_TABLE = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ROWS = 5000;
    private static final int MAX_CELL_CHARS = 200;

    private final DataSource dataSource;
    private final LearningProperties properties;

    public SqlSandboxExecutor(DataSource dataSource, LearningProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    public QueryResult execute(String schema, String sql) {
        return execute(schema, sql, properties.getGrading().getStatementTimeoutMs());
    }

    /** Medium 档任务使用更长的语句超时，但仍受只读事务与行数上限约束。 */
    public QueryResult execute(String schema, String sql, int statementTimeoutMs) {
        if (schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            throw new SqlExecutionException("沙箱标识非法", "22023");
        }
        if (sql == null || sql.isBlank()) {
            throw new SqlExecutionException("SQL 为空", "42601");
        }
        if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > properties.getGrading().getMaxSqlBytes()) {
            throw new SqlExecutionException("SQL 超出允许长度", "54000");
        }
        int timeout = statementTimeoutMs > 0 ? statementTimeoutMs : properties.getGrading().getStatementTimeoutMs();
        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement setup = connection.createStatement()) {
                    setup.execute("SET TRANSACTION READ ONLY");
                    setup.execute("SET LOCAL statement_timeout = " + timeout);
                    setup.execute("SET LOCAL search_path TO " + schema + ", teach, public");
                    setup.execute("SET LOCAL ROLE " + sandboxRole());
                }
                ensureReadRelations(connection, schema, sql);
                List<String> columns = new ArrayList<>();
                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                try (Statement statement = connection.createStatement()) {
                    statement.setMaxRows(MAX_ROWS + 1);
                    try (ResultSet resultSet = statement.executeQuery(sql)) {
                        ResultSetMetaData meta = resultSet.getMetaData();
                        int columnCount = meta.getColumnCount();
                        for (int i = 1; i <= columnCount; i++) {
                            columns.add(meta.getColumnLabel(i));
                        }
                        while (resultSet.next()) {
                            if (rows.size() >= MAX_ROWS) {
                                truncated = true;
                                break;
                            }
                            List<String> row = new ArrayList<>(columnCount);
                            for (int i = 1; i <= columnCount; i++) {
                                Object value = resultSet.getObject(i);
                                row.add(normalize(value));
                            }
                            rows.add(row);
                        }
                    }
                }
                connection.commit();
                long durationMs = (System.nanoTime() - started) / 1_000_000;
                return QueryResult.of(columns, rows, durationMs, truncated);
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                throw translate(failure);
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException failure) {
            throw new SqlExecutionException("无法建立沙箱连接", failure.getSQLState());
        }
    }

    /** 写任务：仅在学员 schema 内执行单条写语句，且只能命中任务声明的目标表。 */
    public int executeUpdate(String schema, String sql, int statementTimeoutMs, String targetTable) {
        if (schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            throw new SqlExecutionException("沙箱标识非法", "22023");
        }
        if (targetTable == null || !SAFE_TABLE.matcher(targetTable).matches()) {
            throw new SqlExecutionException("写任务目标表未配置", "22023");
        }
        if (sql == null || sql.isBlank()) {
            throw new SqlExecutionException("SQL 为空", "42601");
        }
        if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > properties.getGrading().getMaxSqlBytes()) {
            throw new SqlExecutionException("SQL 超出允许长度", "54000");
        }
        int timeout = statementTimeoutMs > 0 ? statementTimeoutMs : properties.getGrading().getStatementTimeoutMs();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement setup = connection.createStatement()) {
                    setup.execute("SET LOCAL statement_timeout = " + timeout);
                    setup.execute("SET LOCAL search_path TO " + schema);
                    setup.execute("SET LOCAL ROLE " + sandboxRole());
                }
                ensureWriteRelations(connection, schema, targetTable, sql);
                int updated;
                try (Statement statement = connection.createStatement()) {
                    updated = statement.executeUpdate(sql);
                }
                connection.commit();
                return updated;
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                throw translate(failure);
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException failure) {
            throw new SqlExecutionException("无法建立沙箱连接", failure.getSQLState());
        }
    }

    private String sandboxRole() {
        String role = properties.getSandbox().getDbRole();
        if (role == null || !SAFE_ROLE.matcher(role).matches()) {
            throw new SqlExecutionException("沙箱执行角色配置非法", "22023");
        }
        return role;
    }

    /**
     * 读路径同样校验真实执行计划：只允许本沙箱与 teach 教学表，
     * 显式 schema（learning/pg_catalog 等）由数据库角色与计划双重拦截。
     */
    private void ensureReadRelations(Connection connection, String schema, String sql) throws SQLException {
        ensureReadNode(explainPlan(connection, sql), schema);
    }

    private void ensureReadNode(JsonNode node, String schema) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode relation = node.get("Relation Name");
            if (relation != null && relation.isTextual()) {
                JsonNode relationSchema = node.get("Schema");
                String actual = relationSchema == null ? "" : relationSchema.asText("");
                if (!schema.equals(actual) && !"teach".equals(actual)) {
                    throw new SqlExecutionException("只能查询当前沙箱与 teach 教学数据", "42501");
                }
            }
            node.fields().forEachRemaining(entry -> ensureReadNode(entry.getValue(), schema));
        } else if (node.isArray()) {
            for (JsonNode child : node) ensureReadNode(child, schema);
        }
    }

    /**
     * 静态守卫会保留双引号标识符内容，但执行计划才是最终事实：
     * 所有关系必须属于本沙箱，ModifyTable 目标必须等于任务声明的目标表。
     */
    private void ensureWriteRelations(Connection connection, String schema, String targetTable, String sql)
            throws SQLException {
        JsonNode plan = explainPlan(connection, sql);
        boolean[] targetSeen = {false};
        ensureWriteNode(plan, schema, targetTable, targetSeen);
        if (!targetSeen[0]) {
            throw new SqlExecutionException("写语句未命中任务目标表：" + targetTable, "42501");
        }
    }

    private void ensureWriteNode(JsonNode node, String schema, String targetTable, boolean[] targetSeen) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode relation = node.get("Relation Name");
            if (relation != null && relation.isTextual()) {
                JsonNode relationSchema = node.get("Schema");
                String actual = relationSchema == null ? "" : relationSchema.asText("");
                if (!schema.equals(actual)) {
                    throw new SqlExecutionException("写任务只能操作沙箱内的表", "42501");
                }
                if ("ModifyTable".equals(node.path("Node Type").asText())) {
                    if (!targetTable.equals(relation.asText())) {
                        throw new SqlExecutionException("写任务只能修改目标表：" + targetTable, "42501");
                    }
                    targetSeen[0] = true;
                }
            }
            node.fields().forEachRemaining(entry -> ensureWriteNode(entry.getValue(), schema, targetTable, targetSeen));
        } else if (node.isArray()) {
            for (JsonNode child : node) ensureWriteNode(child, schema, targetTable, targetSeen);
        }
    }

    private JsonNode explainPlan(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("EXPLAIN (VERBOSE, FORMAT JSON, COSTS FALSE) " + sql)) {
            if (!resultSet.next()) {
                throw new SqlExecutionException("无法解析 SQL 执行计划", "42601");
            }
            try {
                return JSON.readTree(resultSet.getString(1));
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                throw new SqlExecutionException("无法解析 SQL 执行计划", "42601");
            }
        }
    }

    /** 将写任务的目标表恢复为教学初始数据；表名由任务定义提供，需通过白名单校验。 */
    public void resetTable(String schema, String table) {
        if (schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            throw new SqlExecutionException("沙箱标识非法", "22023");
        }
        if (table == null || !table.matches("^[a-z_][a-z0-9_]*$")) {
            throw new SqlExecutionException("目标表名非法", "22023");
        }
        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + schema + "." + table);
                statement.execute("CREATE TABLE " + schema + "." + table + " AS TABLE teach." + table);
            }
        } catch (SQLException failure) {
            throw translate(failure);
        }
    }

    private static String normalize(Object value) throws SQLException {
        if (value == null) return null;
        String text;
        if (value instanceof byte[] bytes) {
            text = HexFormatHolder.hex(bytes);
        } else if (value instanceof java.math.BigDecimal decimal) {
            text = decimal.stripTrailingZeros().toPlainString();
        } else if (value instanceof java.sql.Timestamp timestamp) {
            text = timestamp.toLocalDateTime().toString();
        } else if (value instanceof java.sql.Date date) {
            text = date.toLocalDate().toString();
        } else {
            text = String.valueOf(value);
        }
        return text.length() <= MAX_CELL_CHARS ? text : text.substring(0, MAX_CELL_CHARS);
    }

    private static final class HexFormatHolder {
        private static String hex(byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
    }

    private static SqlExecutionException translate(SQLException failure) {
        String state = failure.getSQLState();
        String message = failure.getMessage() == null ? "SQL 执行失败" : failure.getMessage();
        int newline = message.indexOf('\n');
        if (newline > 0) message = message.substring(0, newline);
        if (message.length() > 320) message = message.substring(0, 320);
        return new SqlExecutionException(message, state);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 回滚失败不应覆盖原始执行错误。
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 连接即将归还池，恢复失败由连接池回收处理。
        }
    }

    public static class SqlExecutionException extends RuntimeException {
        private final String sqlState;

        public SqlExecutionException(String message, String sqlState) {
            super(message);
            this.sqlState = sqlState;
        }

        public String sqlState() {
            return sqlState;
        }
    }
}
