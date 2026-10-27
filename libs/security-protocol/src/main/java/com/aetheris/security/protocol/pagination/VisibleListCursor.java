package com.aetheris.security.protocol.pagination;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * An untrusted keyset position.  It contains no permission decision and must be
 * rebound to the current request's canonical filter and trusted subject.
 */
public record VisibleListCursor(long upper, Long before, int size, String filterHash) {
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 200;

    public static void validateSize(int size) {
        if (size < MIN_SIZE || size > MAX_SIZE) throw invalid();
    }

    public static String hash(String canonicalQuery) {
        if (canonicalQuery == null) throw invalid();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalQuery.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static VisibleListCursor decode(String token, int size, String hash, long initialUpper) {
        validateSize(size);
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw invalid();
        if (token == null || token.isBlank()) return new VisibleListCursor(Math.max(initialUpper, 0), null, size, hash);
        if (token.length() > 160 || !token.matches("v1\\.[0-9]{1,19}\\.[1-9][0-9]{0,18}\\.[1-9][0-9]{0,2}\\.[0-9a-f]{64}")) {
            throw invalid();
        }
        try {
            String[] parts = token.split("\\.");
            long upper = Long.parseLong(parts[1]);
            long before = Long.parseLong(parts[2]);
            int encodedSize = Integer.parseInt(parts[3]);
            if (upper < 0 || before <= 0 || before > upper || encodedSize != size || !parts[4].equals(hash)) {
                throw invalid();
            }
            return new VisibleListCursor(upper, before, size, hash);
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    public String after(long lastScanned) {
        if (lastScanned <= 0 || lastScanned > upper) throw invalid();
        return "v1." + upper + "." + lastScanned + "." + size + "." + filterHash;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("分页游标或筛选条件无效，请刷新列表");
    }
}
