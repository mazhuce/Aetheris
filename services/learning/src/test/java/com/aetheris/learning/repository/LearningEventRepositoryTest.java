package com.aetheris.learning.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningEventRepositoryTest {

    private JdbcTemplate jdbc;
    private LearningRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new LearningRepository(jdbc);
    }

    @Test
    void storesLowSensitivityLearningEvents() {
        repository.insertLearningEvent(1L, "portfolio_exported", "da-basic", null, "{\"labs\":1}");

        verify(jdbc).update(anyString(), eq(1L), eq("portfolio_exported"), eq("da-basic"), isNull(),
                eq("{\"labs\":1}"));
    }

    @Test
    void summarizesEventsWithTheRequestedWindow() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7)))
                .thenReturn(List.of(Map.of("event", "portfolio_exported", "count", 2)));

        List<Map<String, Object>> summary = repository.learningEventSummary(7);

        assertThat(summary).hasSize(1);
        assertThat(summary.get(0)).containsEntry("event", "portfolio_exported").containsEntry("count", 2);
    }
}