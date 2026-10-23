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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private DataSourceSecretService secretService;
    private OutboundTargetPolicy targetPolicy = new OutboundTargetPolicy();
    private boolean allowEmbedded = true; // Explicit no-arg construction is used by isolated JDBC tests.
    private Clock clock = Clock.systemUTC();

    public JdbcService() {
    }

    public JdbcService(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcService(DataSourceSecretService secretService,
                       @org.springframework.beans.factory.annotation.Value("${aetheris.datasource.allow-embedded:false}") boolean allowEmbedded,
                       OutboundTargetPolicy targetPolicy) {
        this.secretService = secretService;
        this.allowEmbedded = allowEmbedded;
        this.targetPolicy = targetPolicy;
    }
    private static final int MAX_QUERY_ROWS = 100;
    private static final int MAX_PREVIEW_ROWS = 50;
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    /** 写入类任务默认上限，远高于交互式查询；可由调用方按任务覆盖。 */
    private static final int WRITE_TIMEOUT_SECONDS = 1800;

    /**
     * Hive / Spark 上执行动态分区写入所需的会话参数。
     *
     * <p>由服务端注入而非让用户写在 SQL 里：{@link SqlWriteGuard} 只放行单条
     * INSERT / CREATE 语句，用户无法自行携带 {@code SET} 语句；而缺少这两个参数时
     * {@code INSERT OVERWRITE ... PARTITION (dt)} 会直接报错。
     */
    private static final List<String> HIVE_WRITE_SESSION_SETTINGS = List.of(
            "SET hive.exec.dynamic.partition = true",
            "SET hive.exec.dynamic.partition.mode = nonstrict");

    /** 已去除下划线并转大写后用于比对，覆盖 allow_load_local_infile 等写法。 */
    private static final List<String> DANGEROUS_PARAM_KEYS = List.of(
            "ALLOWLOADLOCALINFILE", "ALLOWURLINLOCALINFILE", "ALLOWMULTIQUERIES",
            "AUTODESERIALIZE", "QUERYINTERCEPTORS", "STATEMENTINTERCEPTORS",
            "PROPERTIESTRANSFORM", "SERVERCONFIGCACHEFACTORY", "SOCKETFACTORY",
            "DETECTCUSTOMCOLLATIONS", "USELOCALINFILE", "LOCALINFILEINPUTSTREAM",
            "SOCKSPROXYHOST", "AUTHENTICATIONPLUGINS", "DEFAULTAUTHENTICATIONPLUGIN",
            "INITSQL", "SESSIONVARIABLES", "CONNECTIONATTRIBUTES",
            "SSLFACTORY", "SSLHOSTNAMEVERIFIER", "SSLPASSWORDCALLBACK",
            "LOGGERLEVEL", "LOGGERFILE", "LOGUNCLOSEDCONNECTIONS");

    /** Called only by the bounded probe executor; never performs schema/table discovery. */
    Map<String, Object> probe(DataSource dataSource, int timeoutSeconds) throws SQLException {
        try (Connection connection = connect(dataSource)) {
            if (Thread.currentThread().isInterrupted()) throw new java.sql.SQLTimeoutException("Probe cancelled");
            try (Statement statement = connection.createStatement()) {
                try {
                    statement.setQueryTimeout(timeoutSeconds);
                } catch (SQLFeatureNotSupportedException ignored) {
                    log.debug("JDBC driver does not support probe query timeout");
                }
                String probeSql = "derby".equals(normalizedType(dataSource)) ? "VALUES 1" : "SELECT 1";
                try (ResultSet resultSet = statement.executeQuery(probeSql)) {
                    if (!resultSet.next() || resultSet.getInt(1) != 1) {
                        throw new SQLException("Unexpected probe result");
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            // Optional informational metadata must not change the SELECT 1 health observation.
            try {
                DatabaseMetaData metadata = connection.getMetaData();
                result.put("databaseProduct", metadata.getDatabaseProductName());
                result.put("databaseVersion", metadata.getDatabaseProductVersion());
                result.put("driverName", metadata.getDriverName());
                result.put("driverVersion", metadata.getDriverVersion());
            } catch (SQLException metadataError) {
                result.put("driverName", isHiveLike(dataSource) ? "Apache Hive JDBC" : "JDBC Driver");
                log.debug("JDBC informational metadata unavailable during connection probe");
            }
            return result;
        }
    }

    public Map<String, Object> query(DataSource dataSource, String sql) {
        String cleaned = validateReadOnlySql(sql);
        try (Connection connection = connect(dataSource)) {
            prepareReadOnly(connection, dataSource);
            return executeQuery(connection, cleaned, MAX_QUERY_ROWS);
        } catch (SQLException error) {
            logSqlFailure("query", dataSource.getId(), error);
            throw new IllegalStateException(friendlyMessage(error));
        }
    }

    private void prepareReadOnly(Connection connection, DataSource dataSource) throws SQLException {
        if (isPostgresLike(dataSource) || isMysqlLike(dataSource)) {
            // PostgreSQL enforces read-only transactions only with auto-commit disabled.
            // Closing the scoped connection rolls back the read transaction.
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
        }
    }

    /**
     * 执行一条写入语句（数仓加工任务用）。
     *
     * <p>与 {@link #query} 走两条独立路径：查询用 {@code validateReadOnlySql} 拒绝一切写入，
     * 这里用 {@link SqlWriteGuard} 只放行 INSERT / CREATE 白名单。两条路径都不允许多语句。
     *
     * <p>不开启事务：Hive / Spark 不支持事务性 DDL/DML，而 PostgreSQL 上单条语句本身即原子。
     * 任务级的重跑幂等由 {@code INSERT OVERWRITE} 覆盖分区保证，不依赖回滚。
     *
     * @param timeoutSeconds 语句超时；传 null 用默认的写入超时
     * @return 受影响行数（driver 不支持时为 -1）与耗时
     */
    /** Fixed SQL only: a connection or driver timeout never renews the admitted absolute budget. */
    public Map<String, Object> execute(DataSource dataSource, String sql, Integer timeoutSeconds, Instant absoluteDeadline) {
        String sqlStatement = SqlWriteGuard.validate(sql);
        int timeout = timeoutSeconds == null ? WRITE_TIMEOUT_SECONDS : timeoutSeconds;
        if (timeout < 1 || timeout > 86400 || absoluteDeadline == null) {
            throw new IllegalArgumentException("固定 SQL 执行期限无效");
        }
        Instant now = clock.instant();
        var budget = new WriteBudget(absoluteDeadline.isBefore(now.plusSeconds(timeout))
                ? absoluteDeadline : now.plusSeconds(timeout));
        budget.seconds();
        requireWriteCredentials(dataSource);
        long startedAt = System.nanoTime();
        try (Connection connection = connectWrite(dataSource)) {
            budget.seconds();
            if (isHiveLike(dataSource)) {
                for (String setting : HIVE_WRITE_SESSION_SETTINGS) {
                    budget.seconds();
                    try (Statement session = connection.createStatement()) {
                        budget.configure(session);
                        try { session.execute(setting); }
                        catch (SQLException unsupportedSetting) {
                            log.debug("Fixed Hive session setting was not accepted");
                        }
                        budget.seconds();
                    }
                }
            }
            budget.seconds();
            try (Statement jdbcStatement = connection.createStatement()) {
                budget.configure(jdbcStatement);
                jdbcStatement.execute(sqlStatement);
                budget.seconds();
                int affected = jdbcStatement.getUpdateCount();
                budget.seconds();
                var result = new LinkedHashMap<String, Object>();
                result.put("success", true);
                result.put("affectedRows", affected);
                result.put("durationMs", (System.nanoTime() - startedAt) / 1_000_000);
                return result;
            }
        } catch (SQLException error) {
            // No driver message, SQL, URL or credential enters this diagnostic.
            String state = error.getSQLState();
            log.warn("Fixed SQL write failed: errorType={}, sqlState={}", error.getClass().getSimpleName(),
                    state != null && state.matches("[A-Z0-9]{5}") ? state : "unknown");
            throw new IllegalStateException("固定 SQL 执行失败，结果需要对账");
        }
    }

    private final class WriteBudget {
        private final Instant deadline;
        private final long startedNanos = System.nanoTime();
        private final long durationNanos;
        private WriteBudget(Instant deadline) {
            this.deadline = deadline;
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.getSeconds() < 1) throw new IllegalStateException("固定 SQL 执行预算不足");
            this.durationNanos = remaining.compareTo(Duration.ofSeconds(86400)) > 0
                    ? 86400_000_000_000L : remaining.toNanos();
        }
        private int seconds() {
            long wallSeconds = Duration.between(clock.instant(), deadline).getSeconds();
            long monotonicSeconds = (durationNanos - (System.nanoTime() - startedNanos)) / 1_000_000_000L;
            long remaining = Math.min(wallSeconds, monotonicSeconds);
            if (remaining < 1 || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("固定 SQL 执行预算不足");
            }
            return (int) remaining;
        }
        private void configure(Statement statement) throws SQLException {
            int timeout = seconds();
            // Unsupported queryTimeout is a refusal on this path, never an unbounded write.
            statement.setQueryTimeout(timeout);
            int remaining = seconds();
            if (remaining < timeout) {
                statement.setQueryTimeout(remaining);
                if (seconds() < remaining) throw new IllegalStateException("固定 SQL 期限配置耗时超限");
            }
        }
    }

    public Map<String, Object> execute(DataSource dataSource, String sql, Integer timeoutSeconds) {
        String statement = SqlWriteGuard.validate(sql);
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0
                ? WRITE_TIMEOUT_SECONDS
                : timeoutSeconds;
        long startedAt = System.currentTimeMillis();

        requireWriteCredentials(dataSource);
        try (Connection connection = connectWrite(dataSource)) {
            if (isHiveLike(dataSource)) {
                applyHiveWriteSettings(connection);
            }
            try (Statement jdbcStatement = connection.createStatement()) {
                try {
                    jdbcStatement.setQueryTimeout(timeout);
                } catch (SQLFeatureNotSupportedException ignored) {
                    log.debug("JDBC driver does not support query timeout");
                }
                // Hive 的 INSERT 经 execute 返回 false 且 getUpdateCount 为 -1，
                // 这是驱动行为而非失败，因此 -1 只表示「行数未知」。
                jdbcStatement.execute(statement);
                int affected = jdbcStatement.getUpdateCount();

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("affectedRows", affected);
                result.put("durationMs", System.currentTimeMillis() - startedAt);
                return result;
            }
        } catch (SQLException error) {
            logSqlFailure("execute", dataSource.getId(), error);
            // 与其他路径一致：不回显驱动原文，避免泄露连接串或库内结构细节。
            throw new IllegalStateException(friendlyMessage(error));
        }
    }

    /**
     * 将一个受管数据源的只读查询结果批量写入另一个关系型数据源。
     * 目标写入在单个事务中完成，replace 使用同一事务内的 DELETE + INSERT，失败不会留下半批数据。
     */
    public Map<String, Object> transfer(
            DataSource source,
            DataSource target,
            String sourceSql,
            String targetTable,
            String writeMode,
            Integer requestedBatchSize) {
        String query = validateReadOnlySql(sourceSql);
        if (!isPostgresLike(target) && !isMysqlLike(target)) {
            throw new IllegalArgumentException("批量接入目标当前仅支持 PostgreSQL/MySQL 兼容数据源");
        }
        String mode = StringUtils.hasText(writeMode) ? writeMode.trim().toLowerCase(Locale.ROOT) : "append";
        if (!Set.of("append", "replace").contains(mode)) {
            throw new IllegalArgumentException("写入模式仅支持 append 或 replace");
        }
        int batchSize = Math.clamp(requestedBatchSize == null ? 500 : requestedBatchSize, 1, 5000);
        String quotedTable = quoteQualifiedIdentifier(targetTable, target);
        long startedAt = System.currentTimeMillis();
        requireWriteCredentials(target);

        try (Connection sourceConnection = connect(source);
             Connection targetConnection = connectWrite(target);
             Statement sourceStatement = sourceConnection.createStatement()) {
            prepareReadOnly(sourceConnection, source);
            sourceStatement.setFetchSize(batchSize);
            try {
                sourceStatement.setQueryTimeout(WRITE_TIMEOUT_SECONDS);
            } catch (SQLFeatureNotSupportedException ignored) {
                log.debug("Source JDBC driver does not support query timeout");
            }
            targetConnection.setAutoCommit(false);

            try (ResultSet rows = sourceStatement.executeQuery(query)) {
                ResultSetMetaData metadata = rows.getMetaData();
                int columnCount = metadata.getColumnCount();
                if (columnCount == 0) {
                    throw new IllegalArgumentException("来源查询没有可写入的字段");
                }
                List<String> columns = new ArrayList<>(columnCount);
                for (int index = 1; index <= columnCount; index++) {
                    columns.add(quoteIdentifier(metadata.getColumnLabel(index), target));
                }
                String placeholders = String.join(", ", java.util.Collections.nCopies(columnCount, "?"));
                String insertSql = "INSERT INTO " + quotedTable + " (" + String.join(", ", columns)
                        + ") VALUES (" + placeholders + ")";

                if ("replace".equals(mode)) {
                    try (Statement delete = targetConnection.createStatement()) {
                        delete.executeUpdate("DELETE FROM " + quotedTable);
                    }
                }

                long written = 0;
                try (PreparedStatement insert = targetConnection.prepareStatement(insertSql)) {
                    while (rows.next()) {
                        for (int index = 1; index <= columnCount; index++) {
                            insert.setObject(index, rows.getObject(index));
                        }
                        insert.addBatch();
                        written++;
                        if (written % batchSize == 0) {
                            insert.executeBatch();
                        }
                    }
                    if (written % batchSize != 0) {
                        insert.executeBatch();
                    }
                }
                targetConnection.commit();

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("rowsRead", written);
                result.put("rowsWritten", written);
                result.put("durationMs", System.currentTimeMillis() - startedAt);
                return result;
            } catch (RuntimeException | SQLException error) {
                try {
                    targetConnection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (SQLException error) {
            logSqlFailure("transfer", target.getId(), error);
            throw new IllegalStateException(friendlyMessage(error));
        }
    }

    private String quoteQualifiedIdentifier(String value, DataSource target) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("目标表不能为空");
        }
        String[] parts = value.trim().split("\\.");
        if (parts.length < 1 || parts.length > 3) {
            throw new IllegalArgumentException("目标表格式不正确");
        }
        return java.util.Arrays.stream(parts)
                .map(part -> quoteIdentifier(part, target))
                .collect(java.util.stream.Collectors.joining("."));
    }

    private String quoteIdentifier(String value, DataSource target) {
        if (!StringUtils.hasText(value) || !value.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("字段或表名包含不支持的字符");
        }
        String quote = isPostgresLike(target) ? "\"" : "`";
        return quote + value + quote;
    }

    /** 逐条应用动态分区参数；个别引擎不认某个参数时跳过，不影响主语句执行。 */
    private void applyHiveWriteSettings(Connection connection) {
        for (String setting : HIVE_WRITE_SESSION_SETTINGS) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(setting);
            } catch (SQLException error) {
                log.debug("Hive session setting rejected: {}", setting, error);
            }
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
            String quote = usesDoubleQuote(dataSource) ? "\"" : "`";
            String qualified = quote + safeSchema + quote + "." + quote + safeTable + quote;
            String sql = switch (normalizedType(dataSource)) {
                case "sqlserver" -> "SELECT TOP " + MAX_PREVIEW_ROWS + " * FROM " + qualified;
                case "oracle" -> "SELECT * FROM " + qualified + " FETCH FIRST " + MAX_PREVIEW_ROWS + " ROWS ONLY";
                default -> "SELECT * FROM " + qualified + " LIMIT " + MAX_PREVIEW_ROWS;
            };
            return executeQuery(connection, sql, MAX_PREVIEW_ROWS);
        } catch (SQLException error) {
            throw new IllegalStateException(friendlyMessage(error));
        }
    }

    Connection connect(DataSource dataSource) throws SQLException {
        requireBusinessDataSource(dataSource);
        return connectWithCredentials(dataSource, dataSource.getUsername(), dataSource.getPassword());
    }

    Connection connectWrite(DataSource dataSource) throws SQLException {
        requireWriteCredentials(dataSource);
        return connectWithCredentials(dataSource, dataSource.getWriteUsername(), dataSource.getWritePassword());
    }

    private static void requireWriteCredentials(DataSource source) {
        if (source == null || !source.isWriteConfigured())
            throw new IllegalArgumentException("写操作需要完整且独立于读取账户的写入凭据；不能沿用读取账户");
    }

    private Connection connectWithCredentials(DataSource dataSource, String account, String storedPassword) throws SQLException {
        requireBusinessDataSource(dataSource);
        String type = normalizedType(dataSource);
        int port = dataSource.getPort() == null ? defaultPort(type) : dataSource.getPort();
        String username = account == null ? "" : account;
        String password = storedPassword == null ? ""
                : (secretService == null ? storedPassword : secretService.decrypt(storedPassword));
        String url = buildUrl(dataSource, type, port);
        targetPolicy.jdbc(url);
        return DriverManager.getConnection(url, username, password);
    }

    String buildUrl(DataSource dataSource, String type, int port) {
        requireBusinessDataSource(dataSource);
        if (!allowEmbedded && Set.of("h2", "derby", "sqlite").contains(type)) {
            throw new IllegalArgumentException("当前部署未启用嵌入式数据源");
        }
        if (StringUtils.hasText(dataSource.getJdbcUrl())) {
            String jdbcUrl = dataSource.getJdbcUrl().trim();
            validateJdbcUrl(type, jdbcUrl);
            String completeUrl = appendConnectionParams(jdbcUrl, type, dataSource.getConnectionParams());
            validateJdbcUrl(type, completeUrl);
            return completeUrl;
        }

        String database = StringUtils.hasText(dataSource.getDatabaseName())
                ? dataSource.getDatabaseName().trim()
                : defaultDatabase(type);
        if (!Set.of("h2", "derby", "sqlite").contains(type)) {
            if (port < 1 || port > 65535 || !StringUtils.hasText(dataSource.getHost())
                    || !dataSource.getHost().matches("[A-Za-z0-9_.:-]+")
                    || !database.matches("[A-Za-z0-9_$.-]+(?:/[A-Za-z0-9_$.-]+)?")) {
                throw new IllegalArgumentException("主机、端口或数据库名称格式不正确");
            }
        }
        String baseUrl = switch (type) {
            case "postgresql", "greenplum", "opengauss", "cockroachdb", "yugabytedb", "timescaledb" ->
                    "jdbc:postgresql://" + dataSource.getHost() + ":" + port + "/" + database;
            case "mysql", "mariadb", "tidb", "oceanbase", "doris", "starrocks" ->
                    "jdbc:mysql://" + dataSource.getHost() + ":" + port + "/" + database
                            + "?sslMode=VERIFY_IDENTITY&serverTimezone=Asia/Shanghai";
            case "hive2", "spark" ->
                    "jdbc:hive2://" + dataSource.getHost() + ":" + port + "/" + database;
            case "sqlserver" ->
                    "jdbc:sqlserver://" + dataSource.getHost() + ":" + port
                            + ";databaseName=" + database + ";encrypt=true;trustServerCertificate=false";
            case "clickhouse" ->
                    // The lean runtime image intentionally does not bundle optional LZ4/Apache HTTP
                    // client modules. Disable wire compression so the official JDBC driver remains
                    // usable without pulling a second dependency graph into the service image.
                    "jdbc:ch://" + dataSource.getHost() + ":" + port + "/" + database + "?compress=0";
            case "trino" ->
                    "jdbc:trino://" + dataSource.getHost() + ":" + port + "/" + database;
            case "oracle" ->
                    "jdbc:oracle:thin:@//" + dataSource.getHost() + ":" + port + "/" + database;
            case "sqlite" -> "jdbc:sqlite:" + database;
            case "h2" -> "jdbc:h2:" + database;
            case "derby" -> "jdbc:derby:" + database + ";create=true";
            default -> throw new IllegalArgumentException("暂不支持的数据源类型：" + dataSource.getType());
        };
        String completeUrl = appendConnectionParams(baseUrl, type, dataSource.getConnectionParams());
        validateJdbcUrl(type, completeUrl);
        return completeUrl;
    }

    private void requireBusinessDataSource(DataSource dataSource) {
        DataSourceAccessPolicy.requireActiveBusinessSource(dataSource);
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
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW TABLES IN `" + schema + "`")) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            int nameColumn = metadata.getColumnCount() >= 2 ? 2 : 1;
            while (resultSet.next()) {
                names.add(resultSet.getString(nameColumn));
            }
        }
        // SHOW TABLES 不返回表注释，Spark/Iceberg 的 JDBC getTables() 也把 REMARKS 置空，
        // 只能逐表 DESCRIBE FORMATTED 取。表注释是 NL2SQL 命中率的关键信息，值得这一轮额外查询；
        // 单表失败不影响其余表。
        List<DataSourceTableDto> tables = new ArrayList<>(names.size());
        for (String name : names) {
            tables.add(new DataSourceTableDto(name, "TABLE", hiveTableComment(connection, schema, name)));
        }
        return tables;
    }

    /** 从 DESCRIBE FORMATTED 的 Comment 行取表注释；取不到时返回 null，不向上抛。 */
    String hiveTableComment(Connection connection, String schema, String table) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "DESCRIBE FORMATTED `" + schema + "`.`" + table + "`")) {
            while (resultSet.next()) {
                String key = resultSet.getString(1);
                if (key != null && "comment".equals(key.trim().toLowerCase(Locale.ROOT))) {
                    String value = resultSet.getString(2);
                    return StringUtils.hasText(value) ? value.trim() : null;
                }
            }
        } catch (SQLException error) {
            log.debug("Table comment unavailable for {}.{}", schema, table, error);
        }
        return null;
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
        return SqlSafetyPolicy.readOnly(cleaned);
    }

    private String appendConnectionParams(String url, String type, String parameters) {
        if (!StringUtils.hasText(parameters)) {
            return url;
        }
        String cleaned = parameters.trim().replaceFirst("^[?;&]+", "");
        if (cleaned.length() > 1000 || cleaned.contains("\n") || cleaned.contains("\r")) {
            throw new IllegalArgumentException("连接参数格式不正确");
        }
        JdbcConnectionParameters.validate(cleaned);
        if (isHiveLike(type) || "sqlserver".equals(type) || "oracle".equals(type)) {
            return url + ";" + cleaned;
        }
        return url + (url.contains("?") ? "&" : "?") + cleaned.replace(';', '&');
    }

    /**
     * 拒绝可被恶意数据库服务端利用来读取本机文件或执行代码的驱动参数。
     * 采用黑名单以免影响 auth=noSasl 等正常连接参数。
     */
    private void rejectDangerousParams(String parameters) {
        String upper = parameters.toUpperCase(Locale.ROOT).replace("_", "");
        for (String key : DANGEROUS_PARAM_KEYS) {
            if (upper.contains(key)) {
                throw new IllegalArgumentException("连接参数包含不被允许的配置项：" + key);
            }
        }
    }

    private void validateJdbcUrl(String type, String url) {
        String expectedPrefix = switch (type) {
            case "hive2", "spark" -> "jdbc:hive2:";
            case "postgresql", "greenplum", "opengauss", "cockroachdb", "yugabytedb", "timescaledb" -> "jdbc:postgresql:";
            case "mysql", "mariadb", "tidb", "oceanbase", "doris", "starrocks" -> "jdbc:mysql:";
            case "sqlserver" -> "jdbc:sqlserver:";
            case "clickhouse" -> "jdbc:ch:";
            case "trino" -> "jdbc:trino:";
            case "oracle" -> "jdbc:oracle:";
            case "sqlite" -> "jdbc:sqlite:";
            case "h2" -> "jdbc:h2:";
            case "derby" -> "jdbc:derby:";
            default -> throw new IllegalArgumentException("暂不支持的数据源类型：" + type);
        };
        if (!url.toLowerCase(Locale.ROOT).startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("自定义 JDBC URL 与所选数据源类型不匹配");
        }
        // 自定义 URL 自带的参数同样需要校验，否则可绕过 connectionParams 的限制。
        rejectDangerousParams(url);
        JdbcConnectionParameters.validateUrl(type, url);
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

    private static void logSqlFailure(String operation, Long datasourceId, SQLException error) {
        String state = error.getSQLState();
        log.warn("JDBC operation failed: operation={}, datasourceId={}, sqlState={}, vendorCode={}",
                operation, datasourceId, state != null && state.matches("[A-Z0-9]{5}") ? state : "unknown", error.getErrorCode());
    }

    private String friendlyMessage(Exception error) {
        String message = error.getMessage();
        if (!StringUtils.hasText(message)) {
            return "连接失败，请检查服务地址、端口、认证方式与网络访问权限";
        }
        if (message.contains("No suitable driver")) {
            return "缺少对应 JDBC 驱动或 JDBC URL 不正确";
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("connection refused")) {
            return "目标端口拒绝连接，请确认数据库服务已启动并监听外部地址";
        }
        if (lower.contains("password") || lower.contains("authentication")) {
            return "认证失败，请检查用户名与密码";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时，请检查网络连通性与防火墙策略";
        }
        if (lower.contains("unknown host") || lower.contains("unknownhost")) {
            return "无法解析目标主机地址";
        }
        if (lower.contains("does not exist") || lower.contains("unknown database")) {
            return "目标库或表不存在";
        }
        if (lower.contains("permission denied") || lower.contains("access denied")) {
            return "目标库拒绝访问，请确认账号权限";
        }
        // 不回显驱动原文，避免泄露内网主机名、库名与驱动细节。
        return "操作失败，请检查数据源配置与目标库状态";
    }

    private String normalizedType(DataSource dataSource) {
        return dataSource.getType() == null ? "" : dataSource.getType().toLowerCase(Locale.ROOT);
    }

    private boolean isHiveLike(DataSource dataSource) { return isHiveLike(normalizedType(dataSource)); }
    private boolean isHiveLike(String type) { return "hive2".equals(type) || "spark".equals(type); }
    private boolean isPostgresLike(DataSource dataSource) { return isPostgresLike(normalizedType(dataSource)); }
    private boolean isPostgresLike(String type) {
        return Set.of("postgresql", "greenplum", "opengauss", "cockroachdb", "yugabytedb", "timescaledb").contains(type);
    }
    private boolean isMysqlLike(DataSource dataSource) {
        String type = normalizedType(dataSource);
        return Set.of("mysql", "mariadb", "tidb", "oceanbase", "doris", "starrocks").contains(type);
    }

    private boolean usesDoubleQuote(DataSource dataSource) {
        String type = normalizedType(dataSource);
        return isPostgresLike(type) || Set.of("sqlserver", "oracle", "trino", "h2", "derby").contains(type);
    }

    private int defaultPort(String type) {
        return switch (type) {
            case "postgresql", "greenplum", "opengauss", "timescaledb" -> 5432;
            case "cockroachdb" -> 26257;
            case "yugabytedb" -> 5433;
            case "mysql", "mariadb" -> 3306;
            case "tidb" -> 4000;
            case "oceanbase" -> 2881;
            case "doris" -> 9030;
            case "starrocks" -> 9030;
            case "hive2", "spark" -> 10000;
            case "sqlserver" -> 1433;
            case "clickhouse" -> 8123;
            case "trino" -> 8080;
            case "oracle" -> 1521;
            case "sqlite", "h2", "derby" -> 0;
            default -> throw new IllegalArgumentException("暂不支持的数据源类型：" + type);
        };
    }

    private String defaultDatabase(String type) {
        return switch (type) {
            case "postgresql", "greenplum", "opengauss", "cockroachdb", "yugabytedb", "timescaledb" -> "postgres";
            case "hive2", "spark" -> "default";
            case "sqlserver" -> "master";
            case "clickhouse" -> "default";
            case "trino" -> "hive";
            case "oracle" -> "ORCL";
            case "sqlite" -> "aetheris.db";
            case "h2" -> "mem:aetheris";
            case "derby" -> "memory:aetheris";
            default -> "";
        };
    }
}
