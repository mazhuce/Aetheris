package com.aetheris.learning.service;

import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgressServiceTest {

    private LearningRepository repository;
    private ProgressService service;

    @BeforeEach
    void setUp() {
        repository = mock(LearningRepository.class);
        service = new ProgressService(repository);
    }

    private LearningModels.LabRecord lab(String code) {
        LearningModels.LabRecord record = mock(LearningModels.LabRecord.class);
        when(record.code()).thenReturn(code);
        return record;
    }

    @Test
    void issuesOneCredentialPerCompletedPath() {
        LearningModels.LabRecord first = lab("ops-1");
        LearningModels.LabRecord second = lab("ops-2");
        when(repository.hasCredential(1L, "PATH_COMPLETION", "ops-basic")).thenReturn(false);
        when(repository.listLabs("ops-basic")).thenReturn(List.of(first, second));
        when(repository.bestAttempts(1L, "ops-basic")).thenReturn(Map.of(
                "ops-1", new LearningRepository.AttemptRow("ops-1", 90, true, "passed"),
                "ops-2", new LearningRepository.AttemptRow("ops-2", 100, true, "passed")));

        service.issueIfEligible(1L, "ops-basic", "运维入门");

        verify(repository).insertCredential(eq(1L), eq("PATH_COMPLETION"), eq("运维入门 结业证明"), anyString());
    }

    @Test
    void doesNotIssueWhenTheSamePathAlreadyHasACredential() {
        when(repository.hasCredential(1L, "PATH_COMPLETION", "da-basic")).thenReturn(true);

        service.issueIfEligible(1L, "da-basic", "数据分析师入门");

        verify(repository, never()).insertCredential(anyLong(), anyString(), anyString(), anyString());
        verify(repository, never()).listLabs(anyString());
    }

    @Test
    void doesNotIssueWhileAnyLabIsStillUnpassed() {
        LearningModels.LabRecord first = lab("de-1");
        LearningModels.LabRecord second = lab("de-2");
        when(repository.hasCredential(1L, "PATH_COMPLETION", "de-basic")).thenReturn(false);
        when(repository.listLabs("de-basic")).thenReturn(List.of(first, second));
        when(repository.bestAttempts(1L, "de-basic")).thenReturn(Map.of(
                "de-1", new LearningRepository.AttemptRow("de-1", 100, true, "passed"),
                "de-2", new LearningRepository.AttemptRow("de-2", 40, false, "in_progress")));

        service.issueIfEligible(1L, "de-basic", "数据开发入门");

        verify(repository, never()).insertCredential(anyLong(), anyString(), anyString(), anyString());
    }
}