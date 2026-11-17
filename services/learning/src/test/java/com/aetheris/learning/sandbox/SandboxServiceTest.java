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
import static org.mockito.ArgumentMatchers.isNull;
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
        verify(repository, org.mockito.Mockito.never()).insertLearningEvent(eq(9L),
                eq("sandbox_expired_recovered"), any(), any(), any());
    }
    @Test
    void resetDropsPreviousSchemaAndCreatesFreshSandbox() {
        Instant now = Instant.now();
        var existing = new LearningModels.SandboxRow(3L, 9L, "l1-valid-orders", "sb_u9", "active", "light",
                now.plusSeconds(3600), now.minusSeconds(60), null);
        var created = new LearningModels.SandboxRow(4L, 9L, "l1-valid-orders", "sb_u9", "active", "light",
                now.plusSeconds(3600), now, null);
        when(repository.findActiveSandbox(9L))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));

        LearningModels.SandboxInfo info = service.reset(9L, "learner", "l1-valid-orders", "light");

        assertThat(info.schemaName()).isEqualTo("sb_u9");
        verify(repository).markSandboxDropped(3L);
        verify(repository).insertLearningEvent(eq(9L), eq("lab_reset"), isNull(), eq("l1-valid-orders"), isNull());
        verify(jdbcTemplate).execute(org.mockito.ArgumentMatchers.contains("DROP SCHEMA IF EXISTS sb_u9 CASCADE"));
        verify(repository).insertSandbox(eq(9L), eq("learner"), eq("l1-valid-orders"), eq("sb_u9"),
                eq("light"), any(Instant.class));
    }

    @Test
    void expiredSandboxIsDroppedAndRecreatedOnNextUse() {
        Instant now = Instant.now();
        var expired = new LearningModels.SandboxRow(5L, 8L, "l1-valid-orders", "sb_u8", "active", "light",
                now.minusSeconds(60), now.minusSeconds(7200), null);
        var created = new LearningModels.SandboxRow(6L, 8L, "l1-valid-orders", "sb_u8", "active", "light",
                now.plusSeconds(3600), now, null);
        when(repository.findActiveSandbox(8L))
                .thenReturn(Optional.of(expired))
                .thenReturn(Optional.of(created));

        LearningModels.SandboxInfo info = service.ensure(8L, "learner", "l1-valid-orders", "light");

        assertThat(info.expiresAt()).isAfter(now);
        verify(repository).markSandboxDropped(5L);
        verify(repository).insertLearningEvent(eq(8L), eq("sandbox_expired_recovered"), isNull(),
                eq("l1-valid-orders"), isNull());
        verify(jdbcTemplate).execute(org.mockito.ArgumentMatchers.contains("DROP SCHEMA IF EXISTS sb_u8 CASCADE"));
        verify(repository).insertSandbox(eq(8L), eq("learner"), eq("l1-valid-orders"), eq("sb_u8"),
                eq("light"), any(Instant.class));
    }
    @Test
    void everyTeachingTableIsCopiedIntoTheSandbox() {
        LearningModels.SandboxRow created = new LearningModels.SandboxRow(7L, 11L, "l1-valid-orders", "sb_u11",
                "active", "light", Instant.now().plusSeconds(3600), Instant.now(), null);
        when(repository.findActiveSandbox(11L)).thenReturn(Optional.empty()).thenReturn(Optional.of(created));

        service.ensure(11L, "learner", "l1-valid-orders", "light");

        for (String table : java.util.List.of("orders_raw", "order_status_dict", "shop_dim", "users",
                "payments", "refunds", "metric_dict", "daily_arrival", "table_asset")) {
            verify(jdbcTemplate).execute(org.mockito.ArgumentMatchers.contains("AS TABLE teach." + table));
        }
    }
}
