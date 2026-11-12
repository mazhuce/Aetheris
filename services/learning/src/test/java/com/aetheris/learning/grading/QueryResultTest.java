package com.aetheris.learning.grading;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryResultTest {

    @Test
    void checksumIgnoresRowOrder() {
        var left = QueryResult.of(List.of("a"), List.of(List.of("1"), List.of("2")), 1, false);
        var right = QueryResult.of(List.of("a"), List.of(List.of("2"), List.of("1")), 1, false);
        assertThat(left.checksum()).isEqualTo(right.checksum());
    }

    @Test
    void checksumDistinguishesNullFromEmptyString() {
        var withNull = QueryResult.of(List.of("a"), List.of(java.util.Arrays.asList((String) null)), 1, false);
        var withEmpty = QueryResult.of(List.of("a"), List.of(List.of("")), 1, false);
        assertThat(withNull.checksum()).isNotEqualTo(withEmpty.checksum());
    }

    @Test
    void sampleIsBounded() {
        var result = QueryResult.of(List.of("a"), List.of(List.of("1"), List.of("2"), List.of("3")), 1, false);
        assertThat(result.sample(2)).hasSize(2);
        assertThat(result.sample(0)).isEmpty();
    }
}
