package com.aetheris.metadata.dto;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceConnectionInputValidator;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Explicit API whitelist; internal connection revisions, writer identities and all credential columns are excluded. */
public record DataSourceResponse(Long id, String name, String type, String host, Integer port,
                                 String databaseName, String username, boolean writeConfigured, Boolean managed,
                                 String jdbcUrl, String connectionParams, String description, String status,
                                 ConnectionHealth connectionHealth, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                 boolean connectionConfigurationRedacted) {
    private static final Map<String, String> FAILURES = Map.ofEntries(
            Map.entry("TIMEOUT", "连接探测超时，请检查网络、转发链路与目标服务状态"),
            Map.entry("DNS_FAILED", "无法解析目标主机地址"),
            Map.entry("CONNECTION_REFUSED", "目标端口拒绝连接，请检查数据库或 Spark Thrift 服务及转发链路"),
            Map.entry("AUTHENTICATION_FAILED", "认证失败，请检查用户名、密码与认证方式"),
            Map.entry("ACCESS_DENIED", "目标库拒绝访问，请确认连接账号具有查询权限"),
            Map.entry("INVALID_CONFIGURATION", "数据源配置无效，请检查类型、JDBC URL 与连接参数"),
            Map.entry("PROBE_BUSY", "已有连接探测正在进行，或并发已达上限，请稍后重试"),
            Map.entry("PROBE_INTERRUPTED", "连接探测已中断，请稍后重试"),
            Map.entry("PROBE_SUPERSEDED", "配置或探测已更新，本次结果已作废，请重新探测"),
            Map.entry("CONNECTION_FAILED", "连接探测失败，请检查数据源配置与目标服务状态"));

    public static DataSourceResponse from(DataSource source) {
        boolean optionsRedacted = DataSourceConnectionInputValidator.hasUnsafeConnectionOptions(source);
        boolean hostRedacted = DataSourceConnectionInputValidator.hasUnsafeHost(source.getHost());
        boolean databaseRedacted = DataSourceConnectionInputValidator.hasUnsafeDatabaseName(source.getType(), source.getDatabaseName());
        return new DataSourceResponse(source.getId(), source.getName(), source.getType(), hostRedacted ? null : source.getHost(), source.getPort(),
                databaseRedacted ? null : source.getDatabaseName(), source.getUsername(), source.isWriteConfigured(), source.getManaged(),
                optionsRedacted ? null : source.getJdbcUrl(), optionsRedacted ? null : source.getConnectionParams(), source.getDescription(),
                source.getStatus(), publicHealth(source.getConnectionHealth()), source.getCreatedAt(), source.getUpdatedAt(),
                optionsRedacted || hostRedacted || databaseRedacted);
    }

    /** Persisted observations may predate fixed public messages; never forward their free-form failure text. */
    public static ConnectionHealth publicHealth(ConnectionHealth health) {
        if (health == null) return ConnectionHealth.unknown();
        String status = Set.of("unknown", "checking", "available", "unavailable").contains(health.status() == null ? "" : health.status())
                ? health.status() : "unknown";
        String code = health.failureCode() == null ? null
                : FAILURES.containsKey(health.failureCode()) ? health.failureCode() : "CONNECTION_FAILED";
        return new ConnectionHealth(status, health.checkedAt(), health.lastSuccessAt(), health.expiresAt(), health.latencyMs(),
                code, code == null ? null : FAILURES.get(code));
    }

    public static Map<String,Object> publicProbe(Map<String,Object> probe) {
        Map<String,Object> result = new LinkedHashMap<>();
        boolean success = probe != null && Boolean.TRUE.equals(probe.get("success"));
        result.put("success", success);
        String code = probe != null && probe.get("failureCode") instanceof String value && FAILURES.containsKey(value)
                ? value : "CONNECTION_FAILED";
        result.put("message", success ? "连接成功（SELECT 1）" : FAILURES.get(code));
        if (!success) result.put("failureCode", code);
        if (probe != null) {
            if (probe.get("latencyMs") instanceof Number value && value.longValue() >= 0) result.put("latencyMs", value.longValue());
            for (String key : Set.of("databaseProduct", "databaseVersion", "driverName", "driverVersion"))
                if (probe.get(key) instanceof String value) result.put(key, value);
            result.put("connectionHealth", publicHealth(probe.get("connectionHealth") instanceof ConnectionHealth value ? value : null));
        }
        return result;
    }
}
