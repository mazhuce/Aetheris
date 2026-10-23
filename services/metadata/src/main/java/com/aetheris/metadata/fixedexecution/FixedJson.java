package com.aetheris.metadata.fixedexecution;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit fixed-execution wire codec. Never uses the permissive application ObjectMapper. */
final class FixedJson {
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxNumberLength(20)
                    .maxStringLength(768*1024).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static JsonNode parse(byte[] bytes) {
        require(bytes!=null&&bytes.length>0&&bytes.length<=1024*1024);
        try {var value=JSON.readTree(bytes);require(value!=null);return value;}
        catch(java.io.IOException failure){throw invalid();}
    }
    static JsonNode parse(String text) {require(text!=null);return parse(text.getBytes(StandardCharsets.UTF_8));}
    static byte[] bytes(Object value) {
        try{return JSON.writeValueAsBytes(value);}catch(Exception failure){throw invalid();}
    }
    static String string(Object value){return new String(bytes(value),StandardCharsets.UTF_8);}
    static void fields(JsonNode value,String... fields) {
        require(value!=null&&value.isObject()&&value.size()==fields.length);
        var expected=Set.of(fields);value.fieldNames().forEachRemaining(field->require(expected.contains(field)));
    }
    static long number(JsonNode node,String field) {
        var value=node.get(field);require(value!=null&&value.isIntegralNumber()&&value.canConvertToLong());return value.longValue();
    }
    static String text(JsonNode node,String field,int max) {
        var value=node.get(field);require(value!=null&&value.isTextual()&&value.textValue().length()<=max);return value.textValue();
    }
    static boolean bool(JsonNode node,String field) {var value=node.get(field);require(value!=null&&value.isBoolean());return value.booleanValue();}
    static String hash(JsonNode node,String field){String value=text(node,field,64);require(value.matches("[a-f0-9]{64}"));return value;}
    static void username(String value) {
        require(value!=null&&!value.isEmpty()&&value.length()<=64);
        value.codePoints().forEach(point->require(point!='|'&&!Character.isISOControl(point)&&!Character.isWhitespace(point)
                &&!Character.isSpaceChar(point)&&Character.getType(point)!=Character.FORMAT&&!(point>=0xD800&&point<=0xDFFF)));
    }
    static void require(boolean value){if(!value)throw invalid();}
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid fixed execution contract");}
    private FixedJson(){ }
}
