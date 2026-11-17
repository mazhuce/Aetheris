package com.aetheris.learning.service;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.grading.GradingEngine;
import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import com.aetheris.learning.sandbox.SandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionFirstPassEventTest {

    private LearningRepository repository;
    private SandboxService sandboxService;
    private GradingEngine gradingEngine;
    private SubmissionService service;
    private LearningModels.LabRecord lab;

    @BeforeEach
    void setUp() {
        repository = mock(LearningRepository.class);
        sandboxService = mock(SandboxService.class);
        gradingEngine = mock(GradingEngine.class);
        service = new SubmissionService(repository, sandboxService, gradingEngine,
                mock(ProgressService.class), new LearningProperties());

        lab = new LearningModels.LabRecord(1L, "de-l1-01", "de-m1", "第一条查询", "L1", "easy", "SQL",
                "summary", "scenario", "objective", List.of(),
                List.of("teach.orders_raw"), "SELECT *", "SELECT order_id FROM orders_raw",
                com.aetheris.learning.support.Json.readTree("{}"),
                List.of(new LearningModels.RubricItem("result", "结果", 100)), 60, 15, "published", 1, 1,
                "light", List.of(), List.of());
        when(repository.findLab("de-l1-01")).thenReturn(Optional.of(lab));
        when(sandboxService.ensure(1L, "alice", "de-l1-01")).thenReturn(new LearningModels.SandboxInfo(
                "sb_u1", "active", "light", Instant.now().plusSeconds(3600), Instant.now(), null));
        when(repository.nextAttempt(1L, "de-l1-01")).thenReturn(1);
        when(repository.insertSubmission(eq(1L), eq("alice"), eq("de-l1-01"), eq(1), eq(1), anyString(), any()))
                .thenReturn(10L);
        when(gradingEngine.grade(eq(lab), eq("sb_u1"), anyString())).thenReturn(
                new LearningModels.GradedSubmission(100, true, "通过", List.of(), Map.of(),
                        List.of(), List.of(), List.of()));
        when(repository.pathOfLab("de-l1-01")).thenReturn("de-basic");
        when(repository.findPath("de-basic")).thenReturn(Optional.empty());
    }

    @Test
    void firstPassedLabEmitsTheFunnelEvent() {
        when(repository.countPassedLabs(1L)).thenReturn(1);

        service.submit(1L, "alice", "de-l1-01", "SELECT order_id FROM orders_raw", null);

        verify(repository).insertLearningEvent(eq(1L), eq("learner_first_lab_passed"),
                eq("de-basic"), eq("de-l1-01"), anyString());
    }

    @Test
    void laterPassesDoNotEmitTheFunnelEventAgain() {
        when(repository.countPassedLabs(1L)).thenReturn(2);

        service.submit(1L, "alice", "de-l1-01", "SELECT order_id FROM orders_raw", null);

        verify(repository, never()).insertLearningEvent(eq(1L), eq("learner_first_lab_passed"),
                any(), any(), any());
    }
}