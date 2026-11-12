package com.aetheris.learning.sandbox;

import com.aetheris.learning.config.LearningProperties;
import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SandboxServiceTest {

    private LearningRepository repository;
    private JdbcTemplate jdbcTemplate;
    private LearningProperties properties;
    private SandboxService service;

    @BeforeEach
    void setUp() {
        repository = mock(LearningRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        properties = new LearningProperties();
        properties.getSandbox().setMediumConcurrency(2);
        service = new SandboxService(repository, jdbcTemplate, properties);
        when(repository.findActiveSandbox(anyLong())).thenReturn(Optional.empty());
        when(repository.findActiveSandbox(7L)).thenReturn(Optional.of(new LearningModels.SandboxRow(
                1L, 7L, "l6-sla-monitor", "sb_u7", "active", "medium",
                Instant.now().plusSeconds(3600), Instant.now(), null)));
    }

    @Test
    void mediumSlotExhaustionIsRejected() {
        when(repository.countActiveMediumSandboxes(9L)).thenReturn(2);

        assertThatThrownBy(() -> service.ensure(9L, "learner", "l6-sla-monitor", "medium"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Medium 沙箱并发已满");
        verify(repository, org.mockito.Mockito.never())
                .insertSandbox(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void ownerReuseDoesNotConsumeNewSlot() {
        when(repository.countActiveMediumSandboxes(7L)).thenReturn(2);

        LearningModels.SandboxInfo info = service.ensure(7L, "owner", "l6-sla-monitor", "medium");

        assertThat(info.tier()).isEqualTo("medium");
        verify(repository, org.mockito.Mockito.never())
                .insertSandbox(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void lightSandboxBypassesSlotCheck() {
        LearningModels.SandboxRow created = new LearningModels.SandboxRow(2L, 9L, "l1-valid-orders", "sb_u9",
                "active", "light", Instant.now().plusSeconds(3600), Instant.now(), null);
        when(repository.findActiveSandbox(9L)).thenReturn(Optional.empty()).thenReturn(Optional.of(created));

        LearningModels.SandboxInfo info = service.ensure(9L, "learner", "l1-valid-orders", "light");

        assertThat(info.tier()).isEqualTo("light");
        verify(repository).insertSandbox(eq(9L), eq("learner"), eq("l1-valid-orders"), eq("sb_u9"),
                eq("light"), any(Instant.class));
    }
}
