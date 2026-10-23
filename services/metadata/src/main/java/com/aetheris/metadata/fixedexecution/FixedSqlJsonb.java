package com.aetheris.metadata.fixedexecution;

import com.aetheris.security.protocol.BodyHash;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

/** PostgreSQL jsonb::text for the SQL v1 known ASCII keys and strict scalar/array types only. */
final class FixedSqlJsonb {
    private FixedSqlJsonb(){}
    static String hash(JsonNode definition){return BodyHash.sha256(encode(definition).getBytes(StandardCharsets.UTF_8));}
    static String encode(JsonNode n){
        require(n!=null);
        if(n.isNull())return "null";
        if(n.isTextual())return quote(n.textValue());
        if(n.isIntegralNumber()){require(n.canConvertToLong());return Long.toString(n.longValue());}
        if(n.isArray()){var values=new ArrayList<String>();n.forEach(value->values.add(encode(value)));return "["+String.join(", ",values)+"]";}
        require(n.isObject());var keys=new ArrayList<String>();n.fieldNames().forEachRemaining(key->{require(key.matches("[A-Za-z][A-Za-z0-9]*"));keys.add(key);});
        keys.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        return "{"+keys.stream().map(key->quote(key)+": "+encode(n.get(key))).collect(java.util.stream.Collectors.joining(", "))+"}";
    }
    private static String quote(String s){
        var out=new StringBuilder("\"");
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);require(c!=0);
            if(Character.isHighSurrogate(c)){require(i+1<s.length()&&Character.isLowSurrogate(s.charAt(i+1)));out.append(c).append(s.charAt(++i));continue;}
            require(!Character.isLowSurrogate(c));
            switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}
        }
        return out.append('"').toString();
    }
}
