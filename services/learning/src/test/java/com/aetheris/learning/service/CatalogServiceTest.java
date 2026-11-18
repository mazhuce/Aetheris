package com.aetheris.learning.service;

import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import com.aetheris.learning.sandbox.SandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    private LearningRepository repository;
    private CatalogService service;

    @BeforeEach
    void setUp() {
        repository = mock(LearningRepository.class);
        service = new CatalogService(repository, mock(SandboxService.class));
        when(repository.findActiveSandbox(anyLong())).thenReturn(Optional.empty());
        when(repository.bestAttempts(anyLong(), anyString())).thenReturn(Map.of());
        when(repository.datasetRefsOf(anyString())).thenReturn(List.of());
        when(repository.datasetManifests(List.of())).thenReturn(List.of());
        when(repository.findAdminLab(anyString())).thenReturn(Optional.empty());
        when(repository.pathOfLab("da-1")).thenReturn("da-basic");
        when(repository.pathOfLab("da-2")).thenReturn("da-basic");
        when(repository.pathOfLab("da-3")).thenReturn("da-basic");
    }

    private LearningModels.LabRecord lab(String code, String title) {
        LearningModels.LabRecord record = mock(LearningModels.LabRecord.class);
        when(record.code()).thenReturn(code);
        when(record.title()).thenReturn(title);
        when(record.status()).thenReturn("published");
        when(record.level()).thenReturn("L1");
        when(record.difficulty()).thenReturn("easy");
        when(record.taskType()).thenReturn("SQL");
        when(record.steps()).thenReturn(List.of());
        when(record.tableRefs()).thenReturn(List.of());
        when(record.rubric()).thenReturn(List.of());
        when(record.relatedFeatures()).thenReturn(List.of());
        when(record.knowledge()).thenReturn(List.of());
        return record;
    }

    @Test
    void exposesTheNextLabInTheSamePath() {
        LearningModels.LabRecord first = lab("da-1", "第一题");
        LearningModels.LabRecord second = lab("da-2", "第二题");
        when(repository.findLab("da-1")).thenReturn(Optional.of(first));
        when(repository.listLabs("da-basic")).thenReturn(List.of(first, second));

        LearningModels.LabDetail detail = service.labDetail("da-1", 7L);

        assertThat(detail.nextLabCode()).isEqualTo("da-2");
        assertThat(detail.nextLabTitle()).isEqualTo("第二题");
        assertThat(detail.prevLabCode()).isNull();
        assertThat(detail.labPosition()).isEqualTo(1);
        assertThat(detail.labTotal()).isEqualTo(2);
    }

    @Test
    void reportsNoNextLabOnTheLastTask() {
        LearningModels.LabRecord first = lab("da-1", "第一题");
        LearningModels.LabRecord third = lab("da-3", "第三题");
        when(repository.findLab("da-3")).thenReturn(Optional.of(third));
        when(repository.listLabs("da-basic")).thenReturn(List.of(first, third));

        LearningModels.LabDetail detail = service.labDetail("da-3", 7L);

        assertThat(detail.nextLabCode()).isNull();
        assertThat(detail.nextLabTitle()).isNull();
        assertThat(detail.prevLabCode()).isEqualTo("da-1");
        assertThat(detail.prevLabTitle()).isEqualTo("第一题");
        assertThat(detail.labPosition()).isEqualTo(2);
        assertThat(detail.labTotal()).isEqualTo(2);
    }
}