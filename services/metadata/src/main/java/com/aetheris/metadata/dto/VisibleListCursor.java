package com.aetheris.metadata.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Untrusted position, not an authorization token; every request is authorized again. */
public record VisibleListCursor(long upper, Long before, int size, String filterHash) {
    public static void validateSize(int size) { if (size < 1 || size > 200) throw new IllegalArgumentException("size 必须在 1 到 200 之间"); }
    public static String hash(String canonicalQuery) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalQuery.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static VisibleListCursor decode(String cursor, int size, String hash, long initialUpper) {
        validateSize(size);
        if (cursor == null) return new VisibleListCursor(initialUpper, null, size, hash);
        if (cursor.length() > 160 || !cursor.matches("v1\\.[1-9][0-9]{0,18}\\.[1-9][0-9]{0,18}\\.[1-9][0-9]{0,2}\\.[0-9a-f]{64}")) throw invalid();
        try {
            String[] parts = cursor.split("\\.");
            long upper = Long.parseLong(parts[1]), before = Long.parseLong(parts[2]);
            if (before > upper || Integer.parseInt(parts[3]) != size || !parts[4].equals(hash)) throw invalid();
            return new VisibleListCursor(upper, before, size, hash);
        } catch (NumberFormatException failure) { throw invalid(); }
    }
    public String after(long lastConsumed) { return "v1." + upper + "." + lastConsumed + "." + size + "." + filterHash; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("分页游标或筛选条件无效，请刷新列表"); }
}
