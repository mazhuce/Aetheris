package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LineageFieldRelation;
import com.aetheris.metadata.mapper.LineageFieldRelationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
