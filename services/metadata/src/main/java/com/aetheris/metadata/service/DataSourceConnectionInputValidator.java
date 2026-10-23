package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/** Save/response boundary around the same JDBC property allowlist used by the runtime adapter. No connection is opened. */
public final class DataSourceConnectionInputValidator {
    private static final Set<String> EMBEDDED = Set.of("h2", "sqlite", "derby");
    private static final String INVALID = "JDBC 连接配置不合法；凭据只能通过独立账户和密码字段提交";

    private DataSourceConnectionInputValidator() { }

    public static void validateCreate(DataSource source) { validate(source); }

    /** Null patch fields retain their old value, matching the entity's existing MyBatis update strategy. */
    public static void validateUpdate(DataSource patch, DataSource previous) {
        if (patch == null || previous == null) throw invalid();
        boolean connectionChanged = changed(patch.getType(), previous.getType())
                || changed(patch.getHost(), previous.getHost()) || changed(patch.getPort(), previous.getPort())
                || changed(patch.getDatabaseName(), previous.getDatabaseName())
                || changed(patch.getJdbcUrl(), previous.getJdbcUrl()) || changed(patch.getConnectionParams(), previous.getConnectionParams());
        boolean reactivating = "active".equals(patch.getStatus()) && !"active".equals(previous.getStatus());
        if (!connectionChanged && !reactivating) return;
        DataSource merged = new DataSource();
        merged.setType(value(patch.getType(), previous.getType())); merged.setHost(value(patch.getHost(), previous.getHost()));
        merged.setPort(value(patch.getPort(), previous.getPort())); merged.setDatabaseName(value(patch.getDatabaseName(), previous.getDatabaseName()));
        merged.setJdbcUrl(value(patch.getJdbcUrl(), previous.getJdbcUrl()));
        merged.setConnectionParams(value(patch.getConnectionParams(), previous.getConnectionParams()));
        validate(merged);
    }

    /** Historic unsafe options are hidden on every read, without changing the entity used by JDBC/migrations. */
    public static boolean hasUnsafeConnectionOptions(DataSource source) {
        try {
            if (source == null) return true;
            String params = source.getConnectionParams();
            validateParameters(params);
            validateUrlText(source.getJdbcUrl());
            if (text(source.getJdbcUrl())) validateUrl(DataSourceTypeCatalog.require(source.getType()).type(), source.getJdbcUrl());
            return false;
        } catch (IllegalArgumentException error) { return true; }
    }

    /** Public structured fields can contain legacy pasted URLs/properties too; never echo those as a hostname. */
    public static boolean hasUnsafeHost(String host) {
        if (host == null || host.isBlank()) return host != null && !host.isEmpty();
        if (host.matches("[A-Za-z0-9_.-]+")) return false;
        if (!host.matches("[0-9A-Fa-f:.]+") || !host.contains(":")) return true;
        try { return URI.create("http://[" + host + "]").getHost() == null; }
        catch (IllegalArgumentException error) { return true; }
    }

    public static boolean hasUnsafeDatabaseName(String type, String database) {
        if (database == null || database.isEmpty()) return false;
        try {
            validateUrlText(database);
            String normalized = DataSourceTypeCatalog.require(type).type();
            if (EMBEDDED.contains(normalized)) validateUrl(normalized, embeddedUrl(normalized, database));
            else if (!database.trim().matches("[A-Za-z0-9_$.-]+(?:/[A-Za-z0-9_$.-]+)?")) throw invalid();
            return false;
        } catch (IllegalArgumentException error) { return true; }
    }

    private static void validate(DataSource source) {
        if (source == null) throw invalid();
        try {
            var definition = DataSourceTypeCatalog.require(source.getType());
            String type = definition.type();
            validateParameters(source.getConnectionParams());
            validateUrlText(source.getJdbcUrl());
            if (source.getHost() == null || source.getHost().isBlank() || hasUnsafeHost(source.getHost())
                    || hasUnsafeDatabaseName(type, source.getDatabaseName())) throw invalid();
            if (text(source.getJdbcUrl())) {
                validateUrl(type, source.getJdbcUrl());
            } else if (EMBEDDED.contains(type)) {
                String database = text(source.getDatabaseName()) ? source.getDatabaseName().trim() : definition.defaultDatabase();
                validateUrl(type, embeddedUrl(type, database));
            } else {
                int port = source.getPort() == null ? definition.defaultPort() : source.getPort();
                String database = text(source.getDatabaseName()) ? source.getDatabaseName().trim() : definition.defaultDatabase();
                if (source.getHost() == null || !source.getHost().matches("[A-Za-z0-9_.:-]+")
                        || port < 1 || port > 65535 || !database.matches("[A-Za-z0-9_$.-]+(?:/[A-Za-z0-9_$.-]+)?"))
                    throw invalid();
            }
        } catch (IllegalArgumentException error) { throw invalid(); }
    }

    private static void validateParameters(String parameters) {
        if (parameters == null) return;
        if (parameters.length() > 1000 || parameters.indexOf('\r') >= 0 || parameters.indexOf('\n') >= 0 || parameters.indexOf('\0') >= 0) throw invalid();
        if (!text(parameters)) return;
        JdbcConnectionParameters.validate(parameters.trim().replaceFirst("^[?;&]+", ""));
    }

    private static void validateUrlText(String rawUrl) {
        if (rawUrl == null) return;
        if (rawUrl.length() > 8192 || rawUrl.indexOf('\r') >= 0 || rawUrl.indexOf('\n') >= 0 || rawUrl.indexOf('\0') >= 0) throw invalid();
    }

    private static void validateUrl(String type, String rawUrl) {
        validateUrlText(rawUrl);
        String url = rawUrl.trim();
        String prefix = switch (type) {
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
            default -> throw invalid();
        };
        if (!url.regionMatches(true, 0, prefix, 0, prefix.length())) throw invalid();
        JdbcConnectionParameters.validateUrl(type, url);
    }

    private static String embeddedUrl(String type, String database) {
        return "jdbc:" + type + ":" + database + ("derby".equals(type) ? ";create=true" : "");
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static boolean changed(Object proposed, Object previous) { return proposed != null && !Objects.equals(proposed, previous); }
    private static <T> T value(T proposed, T previous) { return proposed == null ? previous : proposed; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException(INVALID); }
}
