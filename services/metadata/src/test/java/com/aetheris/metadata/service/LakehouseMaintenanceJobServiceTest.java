package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseMaintenanceJob;
import com.aetheris.metadata.entity.LakehouseTable;
import com.aetheris.metadata.service.impl.LakehouseMaintenanceJobServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LakehouseMaintenanceJobServiceTest {
    private final LakehouseTableService tableService = mock(LakehouseTableService.class);
    private final LakehouseMaintenanceJobService service = new LakehouseMaintenanceJobServiceImpl(tableService);

    @Test
    void requiresIdempotencyKeyBeforePersistence() {
        when(tableService.getById(1L)).thenReturn(new LakehouseTable());
        LakehouseMaintenanceJob job = new LakehouseMaintenanceJob();
        job.setOperation("expire_snapshots");
        assertThrows(IllegalArgumentException.class, () -> service.request(1L, job, "operator"));
    }

    @Test
    void rejectsUnsupportedOperation() {
        when(tableService.getById(1L)).thenReturn(new LakehouseTable());
        LakehouseMaintenanceJob job = new LakehouseMaintenanceJob();
        job.setOperation("drop_everything");
        assertThrows(IllegalArgumentException.class, () -> service.request(1L, job, "operator"));
    }
}
