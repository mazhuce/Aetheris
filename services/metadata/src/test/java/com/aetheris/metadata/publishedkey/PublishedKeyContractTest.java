package com.aetheris.metadata.publishedkey;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aetheris.metadata.publishedkey.PublishedKeyContract.*;

class PublishedKeyContractTest {
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochMilli(1792281601000L),ZoneOffset.UTC);
    static ObjectNode vector()throws Exception{try(var in=PublishedKeyContractTest.class.getResourceAsStream("/published-key-v1.json")){return (ObjectNode)JSON.readTree(in);}}
    @Test void originalUtf8AndParameterEncodingMatchTheCrossServiceVector()throws Exception{
        var v=vector();String raw=v.get("canonicalAdmission").asText();var ref=new Reference("7feb99a1-1801-46b1-9bba-d8254406de99",v.get("admissionSha256").asText(),v.get("parametersSha256").asText());
        var value=admission(raw,ref,CLOCK);assertEquals("用户甲",value.owner().username());assertEquals(13,Long.parseLong(value.asset().targetRef()));
        assertEquals(v.get("canonicalParameters").asText(),new String(bytes(value.parameters()),StandardCharsets.UTF_8));
        assertEquals("bf1c41b730683546ca92a6e099cf06703f82d5b2cab4d32cc05238d75f80cd0e",value.parameters().sha256());
        assertEquals(ref,reference(bytes(ref)));assertEquals(7,value.owner().uid());
    }
    @Test void referenceRejectsSubjectTargetsCoercionDuplicatesAndTrailingInput(){
        String valid=new String(bytes(new Reference("7feb99a1-1801-46b1-9bba-d8254406de99","a".repeat(64),"b".repeat(64))),StandardCharsets.UTF_8);
        for(String raw:List.of("null","{}",valid+" {}",valid.replace("\"admissionId\":","\"actor\":\"admin\",\"admissionId\":"),
                valid.replace("\"admissionSha256\":","\"admissionSha256\":true,\"admissionSha256\":"),valid.replace("b".repeat(64),"B".repeat(64))))
            assertThrows(IllegalArgumentException.class,()->reference(raw.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->resolveCode(bytes(Map.of("assetCode","orders_api","sql","SELECT 1"))));
        assertThrows(IllegalArgumentException.class,()->reference(new byte[4097]));
    }
    @Test void sourceCannotReplaceAnyBoundReferenceOrExtendTheLease()throws Exception{
        var v=vector();String raw=v.get("canonicalAdmission").asText();var ref=new Reference("7feb99a1-1801-46b1-9bba-d8254406de99",v.get("admissionSha256").asText(),v.get("parametersSha256").asText());
        assertThrows(IllegalArgumentException.class,()->admission(raw+" ",ref,CLOCK));
        var n=(ObjectNode)JSON.readTree(raw);n.put("expiresAtEpochMillis",Long.MAX_VALUE);String changed=JSON.writeValueAsString(n);
        assertThrows(IllegalArgumentException.class,()->admission(changed,new Reference(ref.admissionId(),sha(changed.getBytes(StandardCharsets.UTF_8)),ref.parametersSha256()),CLOCK));
        assertThrows(Rejected.class,()->admission(raw,ref,Clock.offset(CLOCK,Duration.ofSeconds(30))));
        assertThrows(IllegalArgumentException.class,()->admission(raw,new Reference(ref.admissionId(),ref.admissionSha256(),"a".repeat(64)),CLOCK));
    }
    @Test void normalizedParametersRejectDatesUnknownGrainsDuplicateOrUnsafeIdentifiersAndOverflow(){
        assertThrows(IllegalArgumentException.class,()->new Parameters(List.of(),null,"2023-02-29",null,List.of(),100));
        assertThrows(IllegalArgumentException.class,()->new Parameters(List.of(),null,"2024-03-01","2024-02-29",List.of(),100));
        assertThrows(IllegalArgumentException.class,()->new Parameters(List.of(),"MONTH",null,null,List.of(),100));
        assertThrows(IllegalArgumentException.class,()->new Parameters(List.of(),null,null,null,List.of("id","id"),100));
        assertThrows(IllegalArgumentException.class,()->new Parameters(List.of(),null,null,null,List.of("id;delete"),100));
        assertThrows(IllegalArgumentException.class,()->new Parameters(List.of(),null,null,null,List.of(),101));
        assertThrows(IllegalArgumentException.class,()->new Owner(7,"alice|admin",2));assertThrows(IllegalArgumentException.class,()->new Owner(7,"alice\u200b",2));
        assertThrows(IllegalArgumentException.class,()->new Asset(1,"orders_api","v1","table","9223372036854775808"));
        assertEquals("+13",new Asset(1,"orders_api","v1","table","+13").targetRef());
    }
}
