package com.aetheris.learning.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** 学习域 JSON 读写：数据库中的 JSONB 字段统一在这里解析，失败时返回空结构而不是中断请求。 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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

    public static List<String> readStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return MAPPER.readValue(value, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException malformed) {
            return List.of();
        }
    }

    public static <T> List<T> readList(String value, TypeReference<List<T>> type) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonProcessingException malformed) {
            return List.of();
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法序列化学习域数据", failure);
        }
    }

    public static Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException malformed) {
            return Map.of();
        }
    }
}
