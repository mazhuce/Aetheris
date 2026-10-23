package com.aetheris.metadata.service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** User-controlled JDBC properties are a capability allowlist, never driver class names or startup SQL. */
final class JdbcConnectionParameters {
    private static final Set<String> ALLOWED = Set.of(
            "AUTH", "TRANSPORTMODE", "HTTPPATH", "SSL", "SSLMODE", "USESSL", "REQUIRESSL",
            "VERIFYSERVERCERTIFICATE", "ALLOWPUBLICKEYRETRIEVAL", "ENCRYPT", "TRUSTSERVERCERTIFICATE",
            "DATABASENAME", "SERVERTIMEZONE", "CHARACTERENCODING", "USEUNICODE",
            "CONNECTTIMEOUT", "SOCKETTIMEOUT", "LOGINTIMEOUT", "TCPKEEPALIVE", "APPLICATIONNAME",
            "CURRENTSCHEMA", "COMPRESS", "COMPRESSION", "SSLVERIFICATION",
            "DB_CLOSE_DELAY", "DB_CLOSE_ON_EXIT", "MODE", "DATABASE_TO_LOWER", "DATABASE_TO_UPPER",
            "DEFAULT_NULL_ORDERING", "CREATE", "TERRITORY", "COLLATION", "READONLY",
            "REWRITEBATCHEDSTATEMENTS", "CACHEPREPSTMTS", "USESERVERPREPSTMTS");

    private JdbcConnectionParameters() { }

    static void validate(String parameters) {
        if (parameters == null || parameters.isBlank()) return;
        String decoded;
        try { decoded = URLDecoder.decode(parameters, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("JDBC 参数编码不正确"); }
        if (decoded.indexOf('%') >= 0 || decoded.indexOf('\n') >= 0 || decoded.indexOf('\r') >= 0
                || decoded.indexOf('\0') >= 0 || decoded.indexOf('{') >= 0 || decoded.indexOf('}') >= 0) {
            throw new IllegalArgumentException("JDBC 参数包含不支持的转义");
        }
        for (String pair : decoded.split("[;&?]")) {
            if (pair.isBlank()) continue;
            int equals = pair.indexOf('=');
            String key = equals < 1 ? "" : pair.substring(0, equals).trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED.contains(key)) throw new IllegalArgumentException("JDBC 参数不在允许清单内：" + key);
            String value = pair.substring(equals + 1);
            if (value.isBlank() || value.indexOf('=') >= 0 || value.contains("\\") || value.contains("'")) {
                throw new IllegalArgumentException("JDBC 参数值格式不正确");
            }
        }
    }

    static void validateUrl(String type, String url) {
        int boundary = url.indexOf(';');
        int question = url.indexOf('?');
        if (boundary < 0 || (question >= 0 && question < boundary)) boundary = question;
        if (boundary >= 0) validate(url.substring(boundary + 1));
        if (Set.of("h2", "sqlite", "derby").contains(type)) return;
        try {
            String network = url.substring("jdbc:".length(), boundary < 0 ? url.length() : boundary);
            if (network.startsWith("oracle:thin:@//")) network = "oracle://" + network.substring("oracle:thin:@//".length());
            URI uri = URI.create(network);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getHost().contains("%") || uri.getPort() == 0 || uri.getPort() > 65535
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                        && !uri.getRawPath().matches("/[A-Za-z0-9_$./-]*"))) {
                throw new IllegalArgumentException("JDBC URL 必须是结构化的网络连接地址");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("JDBC URL 网络地址格式不正确");
        }
    }
}
