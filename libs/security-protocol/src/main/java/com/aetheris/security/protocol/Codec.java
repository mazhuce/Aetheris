package com.aetheris.security.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

final class Codec {
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(4096).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Codec(){ }
    static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static byte[] decode(String text,int max){
        Rules.require(text!=null && !text.isEmpty() && text.length()<=max && text.matches("[A-Za-z0-9_-]+"),"Invalid compact encoding");
        try {byte[] bytes=Base64.getUrlDecoder().decode(text);Rules.require(encode(bytes).equals(text),"Noncanonical compact encoding");return bytes;}
        catch(IllegalArgumentException malformed){throw new ProtocolException("Invalid compact encoding");}
    }
    static JsonNode object(byte[] bytes,Set<String> fields){
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node=JSON.readTree(text);Rules.require(node!=null && node.isObject(),"Expected JSON object");
            Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);
            Rules.require(actual.equals(fields),"Unexpected or missing proof fields");return node;
        }catch(ProtocolException error){throw error;}
        catch(Exception malformed){throw new ProtocolException("Malformed proof JSON");}
    }
    static String text(JsonNode node,String field){JsonNode value=node.get(field);Rules.require(value!=null && value.isTextual(),"Invalid string claim");return value.textValue();}
    static long number(JsonNode node,String field){JsonNode value=node.get(field);Rules.require(value!=null && value.isIntegralNumber() && value.canConvertToLong(),"Invalid integer claim");return value.longValue();}
    static int integer(JsonNode node,String field){long number=number(node,field);Rules.require(number>=Integer.MIN_VALUE && number<=Integer.MAX_VALUE,"Integer claim overflow");return (int)number;}
    static byte[] bytes(Object value){try{return JSON.writeValueAsBytes(value);}catch(Exception error){throw new ProtocolException("Unable to encode proof");}}
}
