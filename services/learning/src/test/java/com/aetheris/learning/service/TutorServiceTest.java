package com.aetheris.learning.service;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TutorServiceTest {

    private LearningRepository repository;
    private SubmissionService submissionService;
    private AiTutorClient aiTutorClient;
    private TutorService tutorService;
    private LearningModels.LabRecord lab;

    @BeforeEach
    void setUp() {
        repository = mock(LearningRepository.class);
        submissionService = mock(SubmissionService.class);
        aiTutorClient = mock(AiTutorClient.class);
        tutorService = new TutorService(repository, submissionService, aiTutorClient, new LearningProperties());
        lab = new LearningModels.LabRecord(1L, "l3-rfm", "m-l3", "RFM", "L3", "medium", "SQL", "summary",
                "scenario", "objective", List.of(new LearningModels.Step("清洗", "先去重")),
                List.of("teach.orders_raw"), "SELECT 1", "SELECT 2",
                com.aetheris.learning.support.Json.readTree("{}"),
                List.of(new LearningModels.RubricItem("result", "结果", 100)), 70, 30, "published", 1, 1, "light",
                List.of(), List.of());
        when(repository.findLab("l3-rfm")).thenReturn(Optional.of(lab));
        when(repository.latestSubmissionSql(anyLong(), anyString())).thenReturn(Optional.of("SELECT order_id FROM orders_raw"));
        when(aiTutorClient.enabled()).thenReturn(false);
    }

    @Test
    void fallsBackToRulesWhenNoModelConfigured() {
        when(submissionService.lastResult(1L, "l3-rfm")).thenReturn(new LearningModels.GradingView(
                100, true, "通过", List.of(), List.of(), List.of(), List.of()));

        LearningModels.TutorChatView chat = tutorService.chat(1L, "l3-rfm", "还能怎么优化？");

        assertThat(chat.fallback()).isTrue();
        assertThat(chat.model()).isEqualTo("rule-based");
        assertThat(chat.reply()).contains("已经通过");
        verify(repository).insertTutorMessage(eq(1L), eq("l3-rfm"), eq("user"), anyString(), isNull());
        verify(repository).insertTutorMessage(eq(1L), eq("l3-rfm"), eq("assistant"), anyString(), eq("rule-based"));
    }

    @Test
    void usesModelReplyWhenConfigured() {
        when(aiTutorClient.enabled()).thenReturn(true);
        LearningProperties properties = new LearningProperties();
        properties.getAi().setModel("deepseek-chat");
        tutorService = new TutorService(repository, submissionService, aiTutorClient, properties);
        when(submissionService.lastResult(1L, "l3-rfm")).thenReturn(null);
        when(aiTutorClient.complete(anyString(), anyString()))
                .thenReturn(Optional.of(new AiTutorClient.Answer("先检查去重键。", "deepseek-chat")));

        LearningModels.TutorChatView chat = tutorService.chat(1L, "l3-rfm", "为什么行数偏多？");

        assertThat(chat.fallback()).isFalse();
        assertThat(chat.reply()).isEqualTo("先检查去重键。");
        assertThat(chat.model()).isEqualTo("deepseek-chat");
    }

    @Test
    void rejectsOversizedQuestion() {
        assertThatThrownBy(() -> tutorService.chat(1L, "l3-rfm", "x".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insertTutorMessage(anyLong(), anyString(), anyString(), anyString(), any());
    }
    @Test
    void fallsBackToRulesWhenChatQuotaExceeded() {
        when(aiTutorClient.enabled()).thenReturn(true);
        LearningProperties properties = new LearningProperties();
        properties.getTutor().setMaxChatsPerLab(2);
        tutorService = new TutorService(repository, submissionService, aiTutorClient, properties);
        when(repository.countTutorUserMessages(1L, "l3-rfm")).thenReturn(2);
        when(submissionService.lastResult(1L, "l3-rfm")).thenReturn(null);

        LearningModels.TutorChatView chat = tutorService.chat(1L, "l3-rfm", "再给点提示");

        assertThat(chat.fallback()).isTrue();
        assertThat(chat.model()).isEqualTo("rule-based");
        assertThat(chat.reply()).contains("AI 导师对话已达到上限");
        verify(aiTutorClient, never()).complete(anyString(), anyString());
    }
}
