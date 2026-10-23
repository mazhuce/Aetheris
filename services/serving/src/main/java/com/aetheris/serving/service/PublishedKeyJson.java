package com.aetheris.serving.service;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Isolated wire codec. Never change the application's global mapper. */
public final class PublishedKeyJson {
    private PublishedKeyJson(){}
    public static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(16384).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static JsonNode parse(byte[] bytes) {
        if(bytes==null||bytes.length==0||bytes.length>16384)throw bad();
        try{return JSON.readTree(bytes);}catch(Exception invalid){throw bad();}
    }
    public static void fields(JsonNode node,Set<String> fields) {
        if(node==null||!node.isObject()||node.size()!=fields.size())throw bad();
        node.fieldNames().forEachRemaining(f->{if(!fields.contains(f))throw bad();});
    }
    public static String text(JsonNode node,String field,int max){var n=node.get(field);if(n==null||!n.isTextual()||n.textValue().isEmpty()||n.textValue().length()>max)throw bad();return n.textValue();}
    public static long integer(JsonNode node,String field,long min){var n=node.get(field);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<min)throw bad();return n.longValue();}
    public static String hash(JsonNode node,String field){String value=text(node,field,64);if(!value.matches("[a-f0-9]{64}"))throw bad();return value;}
    public static UUID uuid(JsonNode node,String field){String value=text(node,field,36);try{var id=UUID.fromString(value);if(!id.toString().equals(value))throw bad();return id;}catch(IllegalArgumentException invalid){throw bad();}}
    public static byte[] bytes(Object value){try{return JSON.writeValueAsBytes(value);}catch(Exception invalid){throw bad();}}
    public static String string(Object value){return new String(bytes(value),StandardCharsets.UTF_8);}
    public static IllegalArgumentException bad(){return new IllegalArgumentException("发布 API 查询参数无效");}
}
