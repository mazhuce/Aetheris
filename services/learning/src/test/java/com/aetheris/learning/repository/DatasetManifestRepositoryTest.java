package com.aetheris.learning.repository;

import com.aetheris.learning.model.LearningModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatasetManifestRepositoryTest {

    private JdbcTemplate jdbc;
    private LearningRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new LearningRepository(jdbc);
    }

    @Test
    void rejectsCodeThatDoesNotMatchTheTable() {
        assertThatThrownBy(() -> repository.registerDatasetManifest(
                "teach.other", 1, "teach.orders_raw", "internal-training", "synthetic", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一致");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsTablesOutsideTheTeachSchema() {
        assertThatThrownBy(() -> repository.registerDatasetManifest(
                "prod.orders", 1, "prod.orders", "internal-training", "synthetic", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teach");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMissingTableAndMissingProvenance() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("teach.orders_raw"))).thenReturn(false);
        assertThatThrownBy(() -> repository.registerDatasetManifest(
                "teach.orders_raw", 1, "teach.orders_raw", "internal-training", "synthetic", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");

        assertThatThrownBy(() -> repository.registerDatasetManifest(
                "teach.orders_raw", 1, "teach.orders_raw", "", "synthetic", true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("许可");
    }

    @Test
    void computesStatsAndStoresTheNewVersion() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("teach.orders_raw"))).thenReturn(true);
        when(jdbc.queryForMap(contains("FROM teach.orders_raw")))
                .thenReturn(Map.of("row_count", 26, "checksum", "abc123"));

        LearningModels.DatasetManifestView view = repository.registerDatasetManifest(
                "teach.orders_raw", 2, "teach.orders_raw", "internal-training", "real-desensitized", true, "sample");

        assertThat(view.version()).isEqualTo(2);
        assertThat(view.rowCount()).isEqualTo(26);
        assertThat(view.checksum()).isEqualTo("abc123");
        assertThat(view.piiReviewed()).isTrue();
        verify(jdbc).update(anyString(), eq("teach.orders_raw"), eq(2), eq("teach.orders_raw"),
                eq(26), eq("abc123"), eq("internal-training"), eq("real-desensitized"), eq(true), eq("sample"));
    }

    @Test
    void verificationReportsRowAndChecksumDrift() {
        LearningModels.DatasetManifestView manifest = new LearningModels.DatasetManifestView(
                "teach.orders_raw", 1, "teach.orders_raw", 26, "expected", "internal-training",
                "synthetic", true, "published", null);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(manifest));
        when(jdbc.queryForMap(contains("FROM teach.orders_raw")))
                .thenReturn(Map.of("row_count", 27, "checksum", "changed"));

        List<LearningModels.DatasetVerificationView> result = repository.verifyDatasetManifests();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).rowCountMatches()).isFalse();
        assertThat(result.get(0).checksumMatches()).isFalse();
    }
}