package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseTable;
import com.aetheris.metadata.mapper.LakehouseTableSnapshotMapper;
import com.aetheris.metadata.service.impl.LakehouseTableServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class LakehouseTableServiceTest {
    private final LakehouseTableService service = new LakehouseTableServiceImpl(mock(LakehouseTableSnapshotMapper.class));

    @Test
    void rejectsUnsafeCatalogAndTableIdentifiersBeforePersistence() {
        LakehouseTable table = new LakehouseTable();
        table.setCatalogName("catalog;drop");
        table.setNamespaceName("bronze");
        table.setTableName("orders");
        assertThrows(IllegalArgumentException.class, () -> service.register(table, "operator"));
    }

    @Test
    void rejectsEmptyNamespaceParts() {
        LakehouseTable table = new LakehouseTable();
        table.setCatalogName("catalog");
        table.setNamespaceName("bronze..sales");
        table.setTableName("orders");
        assertThrows(IllegalArgumentException.class, () -> service.register(table, "operator"));
    }
}
