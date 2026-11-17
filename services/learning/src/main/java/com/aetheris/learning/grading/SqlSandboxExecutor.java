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
                }
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

    /** 写任务：仅在学员 schema 内执行单条写语句，search_path 不含 teach。 */
    public int executeUpdate(String schema, String sql, int statementTimeoutMs) {
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
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement setup = connection.createStatement()) {
                    setup.execute("SET LOCAL statement_timeout = " + timeout);
                    setup.execute("SET LOCAL search_path TO " + schema);
                }
                ensureSandboxRelations(connection, schema, sql);
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

    /**
     * 静态守卫会剥离引号标识符，/^UPDATE "teach"."orders_raw"/ 可能绕过关键字检查。
     * 执行前用 EXPLAIN 解析真实执行计划，要求所有被引用的关系都属于学员 schema。
     */
    private void ensureSandboxRelations(Connection connection, String schema, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("EXPLAIN (VERBOSE, FORMAT JSON, COSTS FALSE) " + sql)) {
            if (!resultSet.next()) return;
            JsonNode plan = JSON.readTree(resultSet.getString(1));
            ensureSandboxNode(plan, schema);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new SqlExecutionException("无法校验写任务执行计划", "42601");
        }
    }

    private void ensureSandboxNode(JsonNode node, String schema) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode relation = node.get("Relation Name");
            JsonNode relationSchema = node.get("Schema");
            if (relation != null && relation.isTextual()) {
                String actual = relationSchema == null ? "" : relationSchema.asText("");
                if (!schema.equals(actual)) {
                    throw new SqlExecutionException("写任务只能操作沙箱内的表", "42501");
                }
            }
            node.fields().forEachRemaining(entry -> ensureSandboxNode(entry.getValue(), schema));
        } else if (node.isArray()) {
            for (JsonNode child : node) ensureSandboxNode(child, schema);
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
