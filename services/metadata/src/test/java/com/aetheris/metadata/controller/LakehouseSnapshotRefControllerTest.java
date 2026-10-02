package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.LakehouseSnapshotRef;
import com.aetheris.metadata.security.AetherisIdentity;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.LakehouseSnapshotRefService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakehouseSnapshotRefControllerTest {
    private final LakehouseSnapshotRefService service = mock(LakehouseSnapshotRefService.class);
    private final MetadataSecurity security = mock(MetadataSecurity.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final LakehouseSnapshotRefController controller =
            new LakehouseSnapshotRefController(service, security);

    @Test
    void moveAuthorizesAgainstOwningTable() {
        LakehouseSnapshotRef ref = ref(41L, 7L);
        when(service.getById(41L)).thenReturn(ref);
        when(security.require(request, "lakehouse_table", 7L, "update", Map.of()))
                .thenReturn(new AetherisIdentity(1L, "admin"));
        when(security.requireIdentity(request)).thenReturn(new AetherisIdentity(1L, "admin"));

        controller.move(request, 41L, Map.of("snapshotId", "s2"));

        verify(security).require(request, "lakehouse_table", 7L, "update", Map.of());
        verify(service).move(41L, "s2", "admin");
    }

    @Test
    void deleteRejectsUnknownRefBeforePermissionCheck() {
        when(service.getById(404L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> controller.delete(request, 404L));
    }

    private LakehouseSnapshotRef ref(Long id, Long tableId) {
        LakehouseSnapshotRef ref = new LakehouseSnapshotRef();
        ref.setId(id);
        ref.setLakehouseTableId(tableId);
        ref.setRefName("main");
        ref.setRefType("branch");
        return ref;
    }
}