package com.aetheris.workflow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A small SQL Gateway session script: setup statements followed by one job statement. */
public final class FlinkSqlScript {
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_STATEMENTS = 32;

    private FlinkSqlScript() { }

    public static List<String> statements(String sql, boolean streaming) {
        if (sql == null || sql.isBlank() || sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Flink SQL 不能为空且不得超过 1MB");
        }
        if (sql.indexOf('\0') >= 0) throw new IllegalArgumentException("Flink SQL 包含非法字符");
        List<String> statements = split(sql);
        if (statements.isEmpty() || statements.size() > MAX_STATEMENTS) {
            throw new IllegalArgumentException("Flink SQL 语句数量必须为 1 到 32 条");
        }
        String last = leadingSql(statements.getLast()).toUpperCase(Locale.ROOT);
        if (streaming && !last.matches("(?s)^INSERT\\s+INTO\\b.*")) {
            throw new IllegalArgumentException("Flink streaming 脚本必须以 INSERT INTO 结尾");
        }
        if (statements.size() > 1 && !last.matches("(?s)^INSERT\\s+INTO\\b.*")) {
            throw new IllegalArgumentException("多语句 Flink 脚本必须以 INSERT INTO 结尾");
        }
        for (int index = 0; index < statements.size() - 1; index++) {
            String setup = leadingSql(statements.get(index)).toUpperCase(Locale.ROOT);
            if (setup.matches("(?s)^SET\\s+['\"`]?EXECUTION\\.SAVEPOINT\\.PATH\\b.*")) {
                throw new IllegalArgumentException("savepoint 路径只能由受管恢复操作设置");
            }
            if (!setup.matches("(?s)^(SET\\s+|USE\\s+CATALOG\\b|USE\\s+\\S+|CREATE\\s+(?:CATALOG|DATABASE|TABLE|TEMPORARY\\s+TABLE)\\b).*")) {
                throw new IllegalArgumentException("Flink setup 只允许 SET、USE 和 CREATE CATALOG/DATABASE/TABLE");
            }
        }
        return List.copyOf(statements);
    }

    private static List<String> split(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (lineComment) {
                buffer.append(current);
                if (current == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                buffer.append(current);
                if (current == '*' && next == '/') { buffer.append(next); index++; blockComment = false; }
                continue;
            }
            if (quote != 0) {
                buffer.append(current);
                if (current == quote) {
                    if (next == quote) { buffer.append(next); index++; }
                    else quote = 0;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                buffer.append(current).append(next); index++; lineComment = true; continue;
            }
            if (current == '/' && next == '*') {
                buffer.append(current).append(next); index++; blockComment = true; continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current; buffer.append(current); continue;
            }
            if (current == ';') {
                add(result, buffer);
                continue;
            }
            buffer.append(current);
        }
        if (quote != 0 || blockComment) throw new IllegalArgumentException("Flink SQL 引号或注释未闭合");
        add(result, buffer);
        return result;
    }

    private static void add(List<String> result, StringBuilder buffer) {
        String value = buffer.toString().trim();
        buffer.setLength(0);
        if (!leadingSql(value).isBlank()) result.add(value);
    }

    private static String leadingSql(String value) {
        int index = 0;
        while (index < value.length()) {
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            if (value.startsWith("--", index)) {
                int end = value.indexOf('\n', index + 2);
                index = end < 0 ? value.length() : end + 1;
            } else if (value.startsWith("/*", index)) {
                int end = value.indexOf("*/", index + 2);
                index = end < 0 ? value.length() : end + 2;
            } else break;
        }
        return value.substring(index).trim();
    }
}
