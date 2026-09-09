package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.ReleaseRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReleaseGovernanceServiceTest {
    @Test
    void approvedDefinitionCannotBeEdited() {
        ReleaseRecordService records = mock(ReleaseRecordService.class);
        ReleaseRecord existing = new ReleaseRecord(); existing.setId(1L); existing.setStatus("approved");
        when(records.getById(1L)).thenReturn(existing);
        ReleaseGovernanceService service = service(records, mock(JdbcTemplate.class), mock(MetadataImpactClient.class));

        assertThrows(IllegalStateException.class, () -> service.update(1L, new ReleaseRecord()));
        verify(records, never()).updateById(any());
    }

    @Test
    void approvalStoresImpactAndUsesAtomicStateTransition() {
        ReleaseRecordService records = mock(ReleaseRecordService.class);
        ReleaseRecord existing = new ReleaseRecord(); existing.setId(1L); existing.setStatus("pending");
        existing.setReleaseType("table"); existing.setTargetName("dwd.dwd_order");
        when(records.getById(1L)).thenReturn(existing);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        MetadataImpactClient impact = mock(MetadataImpactClient.class);
        when(impact.impact("dwd.dwd_order")).thenReturn(Map.of("downstream", 2));
        ReleaseGovernanceService service = service(records, jdbc, impact);

        service.approve(1L, "alice");

        verify(impact).impact("dwd.dwd_order");
        verify(jdbc).update(contains("status='approved'"), eq("alice"), anyString(), eq(1L), eq("alice"));
    }

    @Test
    void requesterCannotApproveOwnRelease() {
        ReleaseRecordService records = mock(ReleaseRecordService.class);
        ReleaseRecord existing = new ReleaseRecord(); existing.setId(1L); existing.setStatus("pending");
        existing.setRequestedBy("alice"); existing.setReleaseType("table");
        existing.setTargetName("dwd.dwd_order");
        when(records.getById(1L)).thenReturn(existing);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MetadataImpactClient impact = mock(MetadataImpactClient.class);
        ReleaseGovernanceService service = service(records, jdbc, impact);

        assertThrows(IllegalStateException.class, () -> service.approve(1L, "alice"));

        verifyNoInteractions(impact);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private static ReleaseGovernanceService service(ReleaseRecordService records, JdbcTemplate jdbc,
                                                     MetadataImpactClient impact) {
        return new ReleaseGovernanceService(records, jdbc, new ObjectMapper(),
                mock(QualityExecutionClient.class), impact);
    }
}
