package com.aetheris.metadata.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Locale;

/** Fixed public messages only: JDBC exceptions may contain URLs, users and credentials. */
record ConnectionProbeFailure(String code, String message) {
    static ConnectionProbeFailure from(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            String sqlState = current instanceof SQLException sql ? sql.getSQLState() : null;
            if (current instanceof SQLTimeoutException || current instanceof SocketTimeoutException
                    || message.contains("timed out") || message.contains("timeout")) return timeout();
            if (current instanceof UnknownHostException || message.contains("unknown host")
                    || message.contains("unknownhost")) return new ConnectionProbeFailure("DNS_FAILED", "无法解析目标主机地址");
            if (current instanceof ConnectException || message.contains("connection refused"))
                return new ConnectionProbeFailure("CONNECTION_REFUSED", "目标端口拒绝连接，请检查数据库或 Spark Thrift 服务及转发链路");
            if ((sqlState != null && sqlState.startsWith("28")) || message.contains("authentication")
                    || message.contains("password"))
                return new ConnectionProbeFailure("AUTHENTICATION_FAILED", "认证失败，请检查用户名、密码与认证方式");
            if ("42501".equals(sqlState) || message.contains("permission denied") || message.contains("access denied"))
                return new ConnectionProbeFailure("ACCESS_DENIED", "目标库拒绝访问，请确认连接账号具有查询权限");
            if (current instanceof IllegalArgumentException || message.contains("no suitable driver"))
                return new ConnectionProbeFailure("INVALID_CONFIGURATION", "数据源配置无效，请检查类型、JDBC URL 与连接参数");
        }
        return new ConnectionProbeFailure("CONNECTION_FAILED", "连接探测失败，请检查数据源配置与目标服务状态");
    }

    static ConnectionProbeFailure timeout() {
        return new ConnectionProbeFailure("TIMEOUT", "连接探测超时，请检查网络、转发链路与目标服务状态");
    }
}
