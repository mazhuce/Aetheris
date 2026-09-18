package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LineageFieldRelation;
import com.aetheris.metadata.mapper.LineageFieldRelationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FieldLineageServiceTest {
    @Test
    void parsesDirectAndAliasedProjection() {
        LineageFieldRelationMapper mapper = mock(LineageFieldRelationMapper.class);
        when(mapper.insert(any(LineageFieldRelation.class))).thenAnswer(invocation -> 1);
        FieldLineageService service = new FieldLineageService(mapper);
        var result = service.parse("INSERT INTO TABLE dwd.orders (id, amount) SELECT o.order_id AS id, o.total * 1.1 AS amount FROM ods.orders o", "etl", "alice");
        assertEquals(2, result.get("parsed"));
        ArgumentCaptor<LineageFieldRelation> captor = ArgumentCaptor.forClass(LineageFieldRelation.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertEquals("ods.orders", captor.getAllValues().get(0).getSourceTable());
        assertEquals("order_id", captor.getAllValues().get(0).getSourceColumn());
        assertEquals("amount", captor.getAllValues().get(1).getTargetColumn());
    }

    @Test
    void impactReturnsEmptyForUnknownSource() {
        LineageFieldRelationMapper mapper = mock(LineageFieldRelationMapper.class);
        when(mapper.selectBySource("ods.missing", "id")).thenReturn(java.util.List.of());

        assertTrue(new FieldLineageService(mapper).impact("ods.missing", "id").isEmpty());
        verify(mapper).selectBySource("ods.missing", "id");
    }

    @Test
    void impactStopsOnCyclesWithoutDuplicatingRelations() {
        LineageFieldRelationMapper mapper = mock(LineageFieldRelationMapper.class);
        LineageFieldRelation forward = relation("ods.orders", "id", "dwd.orders", "id");
        LineageFieldRelation backward = relation("dwd.orders", "id", "ods.orders", "id");
        when(mapper.selectBySource("ods.orders", "id")).thenReturn(java.util.List.of(forward));
        when(mapper.selectBySource("dwd.orders", "id")).thenReturn(java.util.List.of(backward));

        var result = new FieldLineageService(mapper).impact("ods.orders", "id");

        assertEquals(2, result.size());
        verify(mapper, times(1)).selectBySource("ods.orders", "id");
        verify(mapper, times(1)).selectBySource("dwd.orders", "id");
    }

    @Test
    void impactStopsExpandingAfterConfiguredDepth() {
        LineageFieldRelationMapper mapper = mock(LineageFieldRelationMapper.class);
        when(mapper.selectBySource(any(), any())).thenAnswer(invocation -> {
            String table = invocation.getArgument(0);
            int depth = Integer.parseInt(table.substring(table.indexOf('.') + 1));
            if (depth >= 65) return java.util.List.of();
            return java.util.List.of(relation("ods." + depth, "id", "ods." + (depth + 1), "id"));
        });

        var result = new FieldLineageService(mapper).impact("ods.0", "id");

        assertEquals(64, result.size());
        verify(mapper, never()).selectBySource("ods.64", "id");
    }

    private LineageFieldRelation relation(String sourceTable, String sourceColumn,
                                          String targetTable, String targetColumn) {
        LineageFieldRelation relation = new LineageFieldRelation();
        relation.setSourceTable(sourceTable);
        relation.setSourceColumn(sourceColumn);
        relation.setTargetTable(targetTable);
        relation.setTargetColumn(targetColumn);
        return relation;
    }
}
