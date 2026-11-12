package com.aetheris.learning.grading;

import com.aetheris.learning.support.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlGuardTest {

    private static final String POLICY = """
            {"requireSelect":true,
             "forbiddenKeywords":["insert","update","delete","drop","alter","truncate","grant","create","merge"],
             "requiredColumns":["is_deleted","order_status"],
             "disallowSelectStar":true}
            """;

    @Test
    void acceptsPlainReadOnlyQuery() {
        var evaluation = SqlGuard.evaluate("""
                SELECT order_id, order_status FROM orders_raw
                 WHERE is_deleted = false AND order_status IN ('paid','shipped')
                """, Json.readTree(POLICY));
        assertThat(evaluation.safetyPassed()).isTrue();
        assertThat(evaluation.structurePassed()).isTrue();
    }

    @Test
    void rejectsWriteStatements() {
        var evaluation = SqlGuard.evaluate("UPDATE orders_raw SET quantity = 1", Json.readTree(POLICY));
        assertThat(evaluation.safetyPassed()).isFalse();
        assertThat(evaluation.failures()).anyMatch(message -> message.contains("只读"));
    }

    @Test
    void ignoresKeywordsInsideStringLiterals() {
        var evaluation = SqlGuard.evaluate("""
                SELECT order_id, order_status, 'delete from orders' AS note
                  FROM orders_raw WHERE is_deleted = false AND order_status = 'paid'
                """, Json.readTree(POLICY));
        assertThat(evaluation.safetyPassed()).isTrue();
    }

    @Test
    void rejectsMultipleStatements() {
        var evaluation = SqlGuard.evaluate("""
                SELECT order_id, order_status FROM orders_raw WHERE is_deleted = false;
                SELECT 1;
                """, Json.readTree(POLICY));
        assertThat(evaluation.safetyPassed()).isFalse();
    }

    @Test
    void rejectsSelectStarAndMissingConditions() {
        var evaluation = SqlGuard.evaluate("SELECT * FROM orders_raw", Json.readTree(POLICY));
        assertThat(evaluation.structurePassed()).isFalse();
        assertThat(evaluation.failures()).anyMatch(message -> message.contains("SELECT *"));
        assertThat(evaluation.failures()).anyMatch(message -> message.contains("is_deleted"));
    }

    @Test
    void enforcesRequiredWindowPattern() {
        var policy = Json.readTree("""
                {"forbiddenKeywords":["drop"],"requiredPatterns":["row_number\\\\s*\\\\(","partition\\\\s+by"]}
                """);
        assertThat(SqlGuard.evaluate("""
                SELECT order_id FROM orders_raw
                """, policy).structurePassed()).isFalse();
        assertThat(SqlGuard.evaluate("""
                SELECT order_id, ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY updated_at DESC) rn
                  FROM orders_raw
                """, policy).structurePassed()).isTrue();
    }

    @Test
    void rejectsExplicitPlatformSchemaAccess() {
        var evaluation = SqlGuard.evaluate("""
                SELECT uid, lab_code FROM learning.submission
                """, Json.readTree(POLICY));
        assertThat(evaluation.safetyPassed()).isFalse();
        assertThat(evaluation.failures()).anyMatch(message -> message.contains("平台 schema"));
    }

    @Test
    void rejectsFileAccessFunctions() {
        var evaluation = SqlGuard.evaluate("SELECT pg_read_file('/etc/passwd')", Json.readTree(POLICY));
        assertThat(evaluation.safetyPassed()).isFalse();
        assertThat(evaluation.failures()).anyMatch(message -> message.contains("文件"));
    }

    @Test
    void allowsExplicitTeachingSchema() {
        var evaluation = SqlGuard.evaluate("""
                SELECT order_id, order_status FROM teach.orders_raw
                 WHERE is_deleted = false AND order_status = 'paid'
                """, Json.readTree(POLICY));
        assertThat(evaluation.safetyPassed()).isTrue();
    }

    @Test
    void emptySubmissionIsRejected() {
        var evaluation = SqlGuard.evaluate("  ", Json.readTree(POLICY));
        assertThat(evaluation.safetyPassed()).isFalse();
        assertThat(evaluation.failures()).isNotEmpty();
    }
}
