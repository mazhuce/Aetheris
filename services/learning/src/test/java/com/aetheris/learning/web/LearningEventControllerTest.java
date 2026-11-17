package com.aetheris.learning.web;

import com.aetheris.learning.repository.LearningRepository;
import com.aetheris.learning.security.CurrentUser;
import com.aetheris.learning.service.CatalogService;
import com.aetheris.learning.service.ProgressService;
import com.aetheris.learning.service.SubmissionService;
import com.aetheris.learning.service.TutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LearningEventControllerTest {

    private LearningRepository repository;
    private LearningController controller;
    private CurrentUser user;

    @BeforeEach
    void setUp() {
        repository = mock(LearningRepository.class);
        controller = new LearningController(mock(CatalogService.class), mock(SubmissionService.class),
                mock(ProgressService.class), mock(TutorService.class), repository);
        user = new CurrentUser(1L, "alice", "proof");
    }

    @Test
    void acceptsClientEventsAndStoresThem() {
        var response = controller.event(new LearningController.LearningEventRequest(
                "portfolio_exported", "da-basic", null, "{\"labs\":1}"), user);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(repository).insertLearningEvent(1L, "portfolio_exported", "da-basic", null, "{\"labs\":1}");
    }

    @Test
    void rejectsServerOnlyEventsFromClients() {
        assertThatThrownBy(() -> controller.event(new LearningController.LearningEventRequest(
                "lab_reset", null, "de-l1-01", null), user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的事件");
    }

    @Test
    void rejectsOversizedOrMalformedPayloads() {
        assertThatThrownBy(() -> controller.event(new LearningController.LearningEventRequest(
                "portfolio_exported", null, null, "x".repeat(2049)), user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");

        assertThatThrownBy(() -> controller.event(new LearningController.LearningEventRequest(
                "portfolio_exported", null, null, "{broken"), user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合法 JSON");
    }
}