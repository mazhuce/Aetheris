package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.DataSourceColumnDto;
import com.aetheris.metadata.dto.DataSourceSchemaDto;
import com.aetheris.metadata.dto.DataSourceTableDto;
import com.aetheris.metadata.entity.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class JdbcService {

    private static final Logger log = LoggerFactory.getLogger(JdbcService.class);
    private static final int MAX_QUERY_ROWS = 100;
    private static final int MAX_PREVIEW_ROWS = 50;
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    public Map<String, Object> test(DataSource dataSource) {
        long startedAt = System.currentTimeMillis();
        try (Connection connection = connect(dataSource)) {
            DatabaseMetaData metadata = connection.getMetaData();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "连接成功");
            result.put("latencyMs", System.currentTimeMillis() - startedAt);
            result.put("databaseProduct", metadata.getDatabaseProductName());
            result.put("databaseVersion", metadata.getDatabaseProductVersion());
            try {
                result.put("driverName", metadata.getDriverName());
                result.put("driverVersion", metadata.getDriverVersion());
            } catch (SQLException metadataError) {
                result.put("driverName", isHiveLike(dataSource) ? "Apache Hive JDBC" : "JDBC Driver");
                if (metadata.getDriverMajorVersion() >= 0 && metadata.getDriverMinorVersion() >= 0) {
                    result.put("driverVersion", metadata.getDriverMajorVersion() + "." + metadata.getDriverMinorVersion());
                }
                log.debug("JDBC driver manifest metadata is unavailable", metadataError);
            }
            return result;
        } catch (Exception error) {
            log.warn("Datasource connection test failed: type={}, host={}", dataSource.getType(), dataSource.getHost(), error);
            return Map.of(
                    "success", false,
                    "message", friendlyMessage(error)
            );
        }
    }

    public Map<String, Object> query(DataSource dataSource, String sql) {
        String cleaned = validateReadOnlySql(sql);
        try (Connection connection = connect(dataSource)) {
            return executeQuery(connection, cleaned, MAX_QUERY_ROWS);
        } catch (SQLException error) {
            log.warn("SQL query failed: type={}, host={}", dataSource.getType(), dataSource.getHost(), error);
            throw new IllegalStateException(friendlyMessage(error));
        }
    }

    public List<DataSourceSchemaDto> listSchemas(DataSource dataSource) {
        try (Connection connection = connect(dataSource)) {
            if (isHiveLike(dataSource)) {
                return hiveSchemas(connection);
            }
            Set<String> names = new LinkedHashSet<>();
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet schemas = metadata.getSchemas()) {
                while (schemas.next()) {
                    addNonSystemSchema(names, schemas.getString("TABLE_SCHEM"));
                }
            }
            if (names.isEmpty()) {
                try (ResultSet catalogs = metadata.getCatalogs()) {
                    while (catalogs.next()) {
                        addNonSystemSchema(names, catalogs.getString("TABLE_CAT"));
                    }
                }
            }
            return names.stream().map(DataSourceSchemaDto::new).toList();
        } catch (SQLException error) {
            throw new IllegalStateException(friendlyMessage(error));
        }
    }

    public List<DataSourceTableDto> listTables(DataSource dataSource, String schema) {
        String safeSchema = requireIdentifier(schema, "库/Schema");
        try (Connection connection = connect(dataSource)) {
            if (isHiveLike(dataSource)) {
                return hiveTables(connection, safeSchema);
            }
            List<DataSourceTableDto> tables = new ArrayList<>();
            DatabaseMetaData metadata = connection.getMetaData();
            boolean catalogBased = isMysqlLike(dataSource);
            try (ResultSet resultSet = metadata.getTables(
                    catalogBased ? safeSchema : null,
                    catalogBased ? null : safeSchema,
                    "%",
                    new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW", "EXTERNAL TABLE"})) {
                while (resultSet.next()) {
                    tables.add(new DataSourceTableDto(
                            resultSet.getString("TABLE_NAME"),
                            resultSet.getString("TABLE_TYPE"),
                            resultSet.getString("REMARKS")));
                }
            }
            return tables;
        } catch (SQLException error) {
            throw new IllegalStateException(friendlyMessage(error));
        }
    }

    public List<DataSourceColumnDto> listColumns(DataSource dataSource, String schema, String table) {
        String safeSchema = requireIdentifier(schema, "库/Schema");
        String safeTable = requireIdentifier(table, "表名");
        try (Connection connection = connect(dataSource)) {
            if (isHiveLike(dataSource)) {
                return hiveColumns(connection, safeSchema, safeTable);
            }
            List<DataSourceColumnDto> columns = new ArrayList<>();
            DatabaseMetaData metadata = connection.getMetaData();
            boolean catalogBased = isMysqlLike(dataSource);
            try (ResultSet resultSet = metadata.getColumns(
                    catalogBased ? safeSchema : null,
                    catalogBased ? null : safeSchema,
                    safeTable,
                    "%")) {
                while (resultSet.next()) {
                    columns.add(new DataSourceColumnDto(
                            resultSet.getString("COLUMN_NAME"),
                            resultSet.getString("TYPE_NAME"),
                            resultSet.getInt("COLUMN_SIZE"),
                            resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            resultSet.getInt("ORDINAL_POSITION"),
                            resultSet.getString("REMARKS")));
                }
            }
            return columns;
        } catch (SQLException error) {
            throw new IllegalStateException(friendlyMessage(error));
        }
    }

    public Map<String, Object> preview(DataSource dataSource, String schema, String table) {
        String safeSchema = requireIdentifier(schema, "库/Schema");
        String safeTable = requireIdentifier(table, "表名");
        try (Connection connection = connect(dataSource)) {
            String quote = isPostgresLike(dataSource) ? "\"" : "`";
            String sql = "SELECT * FROM " + quote + safeSchema + quote + "." + quote + safeTable + quote
                    + " LIMIT " + MAX_PREVIEW_ROWS;
            return executeQuery(connection, sql, MAX_PREVIEW_ROWS);
        } catch (SQLException error) {
            throw new IllegalStateException(friendlyMessage(error));
        }
    }

    Connection connect(DataSource dataSource) throws SQLException {
        String type = normalizedType(dataSource);
        int port = dataSource.getPort() == null ? defaultPort(type) : dataSource.getPort();
        DriverManager.setLoginTimeout(8);
        String username = dataSource.getUsername() == null ? "" : dataSource.getUsername();
        String password = dataSource.getPassword() == null ? "" : dataSource.getPassword();
        return DriverManager.getConnection(buildUrl(dataSource, type, port), username, password);
    }

    String buildUrl(DataSource dataSource, String type, int port) {
        if (StringUtils.hasText(dataSource.getJdbcUrl())) {
            String jdbcUrl = dataSource.getJdbcUrl().trim();
            validateJdbcUrl(type, jdbcUrl);
            return appendConnectionParams(jdbcUrl, type, dataSource.getConnectionParams());
        }

        String database = StringUtils.hasText(dataSource.getDatabaseName())
                ? dataSource.getDatabaseName().trim()
                : defaultDatabase(type);
        String baseUrl = switch (type) {
            case "postgresql", "greenplum", "opengauss", "cockroachdb", "yugabytedb" ->
                    "jdbc:postgresql://" + dataSource.getHost() + ":" + port + "/" + database;
            case "mysql", "mariadb", "tidb", "oceanbase", "doris", "starrocks" ->
                    "jdbc:mysql://" + dataSource.getHost() + ":" + port + "/" + database
                            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            case "hive2", "spark" ->
                    "jdbc:hive2://" + dataSource.getHost() + ":" + port + "/" + database;
            default -> throw new IllegalArgumentException("暂不支持的数据源类型：" + dataSource.getType());
        };
        return appendConnectionParams(baseUrl, type, dataSource.getConnectionParams());
    }

    private Map<String, Object> executeQuery(Connection connection, String sql, int maxRows) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(maxRows + 1);
            try {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            } catch (SQLFeatureNotSupportedException ignored) {
                log.debug("JDBC driver does not support query timeout");
            }
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int index = 1; index <= columnCount; index++) {
                    columns.add(metadata.getColumnLabel(index));
                }

                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (resultSet.next()) {
                    if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>();
                    for (int index = 1; index <= columnCount; index++) {
                        row.add(resultSet.getObject(index));
                    }
                    rows.add(row);
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("columns", columns);
                result.put("rows", rows);
                result.put("rowCount", rows.size());
                result.put("truncated", truncated);
                return result;
            }
        }
    }

    private List<DataSourceSchemaDto> hiveSchemas(Connection connection) throws SQLException {
        List<DataSourceSchemaDto> schemas = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW DATABASES")) {
            while (resultSet.next()) {
                schemas.add(new DataSourceSchemaDto(resultSet.getString(1)));
            }
        }
        return schemas;
    }

    private List<DataSourceTableDto> hiveTables(Connection connection, String schema) throws SQLException {
        List<DataSourceTableDto> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW TABLES IN `" + schema + "`")) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            int nameColumn = metadata.getColumnCount() >= 2 ? 2 : 1;
            while (resultSet.next()) {
                tables.add(new DataSourceTableDto(resultSet.getString(nameColumn), "TABLE", null));
            }
        }
        return tables;
    }

    private List<DataSourceColumnDto> hiveColumns(Connection connection, String schema, String table) throws SQLException {
        List<DataSourceColumnDto> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("DESCRIBE `" + schema + "`.`" + table + "`")) {
            int ordinal = 1;
            while (resultSet.next()) {
                String name = resultSet.getString(1);
                if (!StringUtils.hasText(name) || name.trim().startsWith("#")) {
                    continue;
                }
                String type = resultSet.getMetaData().getColumnCount() >= 2 ? resultSet.getString(2) : "unknown";
                String comment = resultSet.getMetaData().getColumnCount() >= 3 ? resultSet.getString(3) : null;
                columns.add(new DataSourceColumnDto(name.trim(), type, null, true, ordinal++, comment));
            }
        }
        return columns;
    }

    private String validateReadOnlySql(String sql) {
        String cleaned = sql == null ? "" : sql.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String upper = cleaned.toUpperCase(Locale.ROOT);
        if (cleaned.contains(";")) {
            throw new IllegalArgumentException("仅支持单条查询语句");
        }
        if (!(upper.startsWith("SELECT") || upper.startsWith("SHOW")
                || upper.startsWith("DESCRIBE") || upper.startsWith("DESC")
                || upper.startsWith("EXPLAIN") || upper.startsWith("WITH"))) {
            throw new IllegalArgumentException("仅支持 SELECT / SHOW / DESCRIBE / EXPLAIN 查询");
        }
        String[] forbidden = {
                "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
                "GRANT", "REVOKE", "CALL", "MERGE", "COPY", "VACUUM", "ATTACH", "DETACH"
        };
        for (String word : forbidden) {
            if (upper.matches(".*\\b" + word + "\\b.*")) {
                throw new IllegalArgumentException("查询中包含不允许的关键字：" + word);
            }
        }
        return cleaned;
    }

    private String appendConnectionParams(String url, String type, String parameters) {
        if (!StringUtils.hasText(parameters)) {
            return url;
        }
        String cleaned = parameters.trim().replaceFirst("^[?;&]+", "");
        if (cleaned.length() > 1000 || cleaned.contains("\n") || cleaned.contains("\r")) {
            throw new IllegalArgumentException("连接参数格式不正确");
        }
        if (isHiveLike(type)) {
            return url + ";" + cleaned;
        }
        return url + (url.contains("?") ? "&" : "?") + cleaned.replace(';', '&');
    }

    private void validateJdbcUrl(String type, String url) {
        String expectedPrefix = isHiveLike(type)
                ? "jdbc:hive2:"
                : isPostgresLike(type) ? "jdbc:postgresql:" : "jdbc:mysql:";
        if (!url.toLowerCase(Locale.ROOT).startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("自定义 JDBC URL 与所选数据源类型不匹配");
        }
    }

    private String requireIdentifier(String value, String field) {
        if (!StringUtils.hasText(value) || !value.matches("[A-Za-z0-9_$-]+")) {
            throw new IllegalArgumentException(field + "包含不支持的字符");
        }
        return value;
    }

    private void addNonSystemSchema(Set<String> names, String name) {
        if (StringUtils.hasText(name)
                && !name.equalsIgnoreCase("information_schema")
                && !name.startsWith("pg_")) {
            names.add(name);
        }
    }

    private String friendlyMessage(Exception error) {
        String message = error.getMessage();
        if (!StringUtils.hasText(message)) {
            return "连接失败，请检查服务地址、端口、认证方式与网络访问权限";
        }
        if (message.contains("No suitable driver")) {
            return "缺少对应 JDBC 驱动或 JDBC URL 不正确";
        }
        if (message.toLowerCase(Locale.ROOT).contains("connection refused")) {
            return "目标端口拒绝连接，请确认数据库服务已启动并监听外部地址";
        }
        return message;
    }

    private String normalizedType(DataSource dataSource) {
        return dataSource.getType() == null ? "" : dataSource.getType().toLowerCase(Locale.ROOT);
    }

    private boolean isHiveLike(DataSource dataSource) { return isHiveLike(normalizedType(dataSource)); }
    private boolean isHiveLike(String type) { return "hive2".equals(type) || "spark".equals(type); }
    private boolean isPostgresLike(DataSource dataSource) { return isPostgresLike(normalizedType(dataSource)); }
    private boolean isPostgresLike(String type) {
        return Set.of("postgresql", "greenplum", "opengauss", "cockroachdb", "yugabytedb").contains(type);
    }
    private boolean isMysqlLike(DataSource dataSource) {
        String type = normalizedType(dataSource);
        return Set.of("mysql", "mariadb", "tidb", "oceanbase", "doris", "starrocks").contains(type);
    }

    private int defaultPort(String type) {
        return switch (type) {
            case "postgresql", "greenplum", "opengauss" -> 5432;
            case "cockroachdb" -> 26257;
            case "yugabytedb" -> 5433;
            case "mysql", "mariadb" -> 3306;
            case "tidb" -> 4000;
            case "oceanbase" -> 2881;
            case "doris" -> 9030;
            case "starrocks" -> 9030;
            case "hive2", "spark" -> 10000;
            default -> throw new IllegalArgumentException("暂不支持的数据源类型：" + type);
        };
    }

    private String defaultDatabase(String type) {
        return switch (type) {
            case "postgresql", "greenplum", "opengauss", "cockroachdb", "yugabytedb" -> "postgres";
            case "hive2", "spark" -> "default";
            default -> "";
        };
    }
}
