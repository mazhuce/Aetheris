package com.aetheris.learning.service;

import com.aetheris.learning.model.LearningModels;
import com.aetheris.learning.repository.LearningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningAdminServiceTest {

    private LearningRepository repository;
    private LearningAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(LearningRepository.class);
        service = new LearningAdminService(repository);
    }

    @Test
    void mcpPublishIsRejectedBeforeContentReview() {
        when(repository.findApproval(9L)).thenReturn(Optional.of(approval("LAB_PUBLISH", "l3-rfm", "pending")));
        when(repository.findAdminLab("l3-rfm")).thenReturn(Optional.of(lab("draft")));

        assertThatThrownBy(() -> service.decide(9L, true, 1L, "admin", "ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未通过审核");
        verify(repository, never()).decideApproval(anyLong(), anyBoolean(), anyLong(), anyString(), anyString());
    }

    @Test
    void approvingPublishRequestPublishesReviewedLab() {
        when(repository.findApproval(9L)).thenReturn(Optional.of(approval("LAB_PUBLISH", "l3-rfm", "pending")));
        when(repository.findAdminLab("l3-rfm")).thenReturn(Optional.of(lab("approved")));
        when(repository.decideApproval(9L, true, 1L, "admin", "ok")).thenReturn(1);
        when(repository.publishReviewedLab("l3-rfm")).thenReturn(1);

        var result = service.decide(9L, true, 1L, "admin", "ok");

        assertThat(result.labPublished()).isTrue();
        assertThat(result.status()).isEqualTo("approved");
        verify(repository).publishReviewedLab("l3-rfm");
    }

    @Test
    void alreadyDecidedApprovalCannotBeDecidedAgain() {
        when(repository.findApproval(9L)).thenReturn(Optional.of(approval("LAB_PUBLISH", "l3-rfm", "approved")));

        assertThatThrownBy(() -> service.decide(9L, true, 1L, "admin", "ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已处理");
    }

    @Test
    void invalidLabCodeIsRejectedOnCreate() {
        var request = new LearningAdminService.LabUpsert("m-l1-sql", "Bad Code", "标题", "L1", "easy",
                "summary", "scenario", "objective", List.of(), List.of("teach.orders_raw"), "", "SELECT 1", 70, 20, 1,
                null, List.of());

        assertThatThrownBy(() -> service.create(request, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务编码");
    }

    private static LearningModels.ApprovalView approval(String type, String target, String status) {
        return new LearningModels.ApprovalView(9L, type, target, "{}", "reason", status, 1L, "admin",
                null, null, Instant.now(), null);
    }

    private static LearningModels.LabAdminView lab(String reviewStatus) {
        return new LearningModels.LabAdminView(1L, "l3-rfm", "m-l3", null, "RFM", "L3", "medium", "summary",
                "objective", "draft", reviewStatus, null, "admin", 1, 70, 30, "SELECT 1", Instant.now());
    }
}
