package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseSnapshotRef;
import com.aetheris.metadata.mapper.LakehouseTableSnapshotMapper;
import com.aetheris.metadata.service.impl.LakehouseSnapshotRefServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class LakehouseSnapshotRefServiceTest {
    private final LakehouseSnapshotRefService service = new LakehouseSnapshotRefServiceImpl(
            mock(LakehouseTableService.class), mock(LakehouseTableSnapshotMapper.class));

    @Test
    void rejectsUnsafeRefNameBeforeDatabaseAccess() {
        LakehouseSnapshotRef ref = new LakehouseSnapshotRef();
        ref.setRefName("branch;drop");
        ref.setRefType("branch");
        ref.setSnapshotId("s1");
        assertThrows(IllegalArgumentException.class, () -> service.create(1L, ref, "operator"));
    }
}
