package com.aetheris.workflow.fixedexecution;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Strict service-local wire parser. PostgreSQL alone supplies canonical manifest bytes. */
public final class FixedJson {
    public static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(1048576).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private FixedJson() { }
    public static JsonNode parse(byte[] raw,int max) {
        if(raw==null||raw.length==0||raw.length>max)throw invalid();
        try {return JSON.readTree(raw);}catch(Exception bad){throw invalid();}
    }
    public static JsonNode parse(String raw){if(raw==null)throw invalid();return parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),1048576);}
    public static void fields(JsonNode n,String... fields){
        if(n==null||!n.isObject()||n.size()!=fields.length)throw invalid();var names=Set.of(fields);
        n.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw invalid();});
    }
    public static long number(JsonNode n,String key,long minimum){var v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<minimum)throw invalid();return v.longValue();}
    public static String text(JsonNode n,String key){var v=n.get(key);if(v==null||!v.isTextual())throw invalid();return v.textValue();}
    public static String hash(JsonNode n,String key){var value=text(n,key);if(!value.matches("[a-f0-9]{64}"))throw invalid();return value;}
    public static byte[] bytes(Object object){try{return JSON.writeValueAsBytes(object);}catch(Exception bad){throw invalid();}}
    public static IllegalArgumentException invalid(){return new IllegalArgumentException("固定执行请求或证据格式无效");}
}
