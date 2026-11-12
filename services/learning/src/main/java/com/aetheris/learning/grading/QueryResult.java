package com.aetheris.learning.grading;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/** 一次沙箱查询的规范化结果。行序不参与比较，按多重集计算摘要。 */
public record QueryResult(List<String> columns, List<List<String>> rows, int rowCount, String checksum,
                          long durationMs, boolean truncated) {

    public static QueryResult of(List<String> columns, List<List<String>> rows, long durationMs, boolean truncated) {
        return new QueryResult(List.copyOf(columns), List.copyOf(rows), rows.size(), checksum(rows), durationMs, truncated);
    }

    public static String checksum(List<List<String>> rows) {
        List<String> lines = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) line.append('\u0001');
                String cell = row.get(i);
                if (cell == null) {
                    line.append("-1:");
                } else {
                    line.append(cell.length()).append(':').append(cell);
                }
            }
            lines.add(line.toString());
        }
        Collections.sort(lines);
        return sha256(String.join("\n", lines));
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 不可用", missing);
        }
    }

    public List<List<String>> sample(int limit) {
        if (limit <= 0) return List.of();
        return rows.size() <= limit ? rows : rows.subList(0, limit);
    }
}
