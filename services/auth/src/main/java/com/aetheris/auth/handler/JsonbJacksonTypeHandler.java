package com.aetheris.auth.handler;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** Preserve Jackson's typed decoding, but bind writes as PostgreSQL JSONB. */
public class JsonbJacksonTypeHandler extends JacksonTypeHandler {
    public JsonbJacksonTypeHandler(Class<?> type) { super(type); }

    public JsonbJacksonTypeHandler(Class<?> type, Field field) { super(type, field); }

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Object value, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, toJson(value), Types.OTHER);
    }
}
