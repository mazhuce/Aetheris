package com.aetheris.mcp.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** MCP 服务的最小 JSON 工具，避免各层重复创建 ObjectMapper。 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static JsonNode readTree(String value) {
        if (value == null || value.isBlank()) return MAPPER.createObjectNode();
        try {
            return MAPPER.readTree(value);
        } catch (JsonProcessingException malformed) {
            return MAPPER.createObjectNode();
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法序列化 MCP 数据", failure);
        }
    }
}
