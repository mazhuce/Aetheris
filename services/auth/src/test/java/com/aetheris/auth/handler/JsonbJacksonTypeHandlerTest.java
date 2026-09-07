package com.aetheris.auth.handler;

import com.aetheris.auth.entity.ResourceGrant;
import com.aetheris.auth.model.DataScope;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class JsonbJacksonTypeHandlerTest {
    @Test
    void actionArrayBindsAsJsonbAndDecodesAsList() throws Exception {
        var handler = new JsonbJacksonTypeHandler(List.class, ResourceGrant.class.getDeclaredField("actions"));
        PreparedStatement statement = mock(PreparedStatement.class);
        handler.setNonNullParameter(statement, 1, List.of("view", "query"), JdbcType.OTHER);
        verify(statement).setObject(1, "[\"view\",\"query\"]", Types.OTHER);
        ResultSet result = mock(ResultSet.class);
        when(result.getString("actions")).thenReturn("[\"view\",\"query\"]");
        assertEquals(List.of("view", "query"), handler.getNullableResult(result, "actions"));
    }

    @Test
    void dataScopeBindsAsJsonbAndPreservesTypedDecoding() throws Exception {
        var handler = new JsonbJacksonTypeHandler(DataScope.class, ResourceGrant.class.getDeclaredField("dataScope"));
        PreparedStatement statement = mock(PreparedStatement.class);
        handler.setNonNullParameter(statement, 2, new DataScope("domain", List.of("5")), JdbcType.OTHER);
        verify(statement).setObject(eq(2), anyString(), eq(Types.OTHER));
        ResultSet result = mock(ResultSet.class);
        when(result.getString("data_scope")).thenReturn("{\"kind\":\"domain\",\"values\":[\"5\"]}");
        DataScope decoded = (DataScope) handler.getNullableResult(result, "data_scope");
        assertEquals("domain", decoded.getKind());
        assertEquals(List.of("5"), decoded.getValues());
    }
}
