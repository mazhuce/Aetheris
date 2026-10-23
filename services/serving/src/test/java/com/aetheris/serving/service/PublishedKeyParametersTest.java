package com.aetheris.serving.service;

import com.aetheris.security.protocol.BodyHash;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aetheris.serving.service.PublishedKeyJson.*;

class PublishedKeyParametersTest {
    static byte[] raw(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test void sharedCrossServiceBytesAndUnicodeVectorRemainExact()throws Exception {
        var vector=JSON.readTree(getClass().getResourceAsStream("/published-key-v1.json"));
        String canonical=vector.get("canonicalParameters").textValue();var p=PublishedKeyParameters.from(PublishedKeyParameters.publicRequest(raw(canonical)));
        assertEquals(canonical,string(p.map()));assertEquals(vector.get("parametersSha256").textValue(),p.sha256());
        String admission=vector.get("canonicalAdmission").textValue();assertTrue(admission.contains("用户甲"));assertFalse(admission.contains("\\u7528"));assertEquals(vector.get("admissionSha256").textValue(),BodyHash.sha256(raw(admission)));
    }
    @Test void defaultsAndStableDistinctListsHaveOneEncoding() {
        var p=PublishedKeyParameters.from(PublishedKeyParameters.publicRequest(raw("{\"dimensions\":[\"region\",\"region\"],\"timeGrain\":\" MONTH \",\"columns\":[\"id\",\"id\"]}")));
        assertEquals(List.of("region"),p.dimensions());assertEquals(List.of("id"),p.columns());assertEquals("month",p.timeGrain());assertEquals(100,p.limit());
        assertEquals("{\"dimensions\":[],\"timeGrain\":null,\"startDate\":null,\"endDate\":null,\"columns\":[],\"limit\":100}",string(PublishedKeyParameters.from(null).map()));
    }
    @Test void strictJsonRejectsCoercionUnknownDuplicatesAndTrailingData() {
        for(String value:List.of("null","[]","{\"sql\":\"SELECT 1\"}","{\"limit\":1,\"limit\":2}","{}{}","{\"limit\":true}","{\"limit\":\"1\"}","{\"limit\":1.0}","{\"limit\":9223372036854775808}","{\"dimensions\":\"ab\"}","{\"columns\":[null]}","{\"timeGrain\":false}"))assertThrows(IllegalArgumentException.class,()->PublishedKeyParameters.publicRequest(raw(value)),value);
        assertThrows(IllegalArgumentException.class,()->PublishedKeyParameters.publicRequest(raw(" ".repeat(16385))));
    }
    @Test void realCalendarDateRangeNamesLengthsAndLimitsAreValidated() {
        assertDoesNotThrow(()->PublishedKeyParameters.publicRequest(raw("{\"startDate\":\"2024-02-29\",\"endDate\":\"2024-03-01\",\"limit\":100}")));
        for(String value:List.of("{\"startDate\":\"2023-02-29\"}","{\"startDate\":\"2024-04-31\"}","{\"startDate\":\"2024-03-02\",\"endDate\":\"2024-03-01\"}","{\"timeGrain\":\"week\"}","{\"dimensions\":[\"x;DROP\"]}","{\"columns\":[\"id,name\"]}","{\"limit\":0}","{\"limit\":101}","{\"columns\":[\""+"a".repeat(129)+"\"]}"))assertThrows(IllegalArgumentException.class,()->PublishedKeyParameters.publicRequest(raw(value)),value);
        assertThrows(IllegalArgumentException.class,()->new PublishedKeyParameters(Collections.nCopies(9,"region"),null,null,null,List.of(),1));
        assertThrows(IllegalArgumentException.class,()->new PublishedKeyParameters(List.of(),null,null,null,Collections.nCopies(51,"id"),1));
    }
    @Test void referencesAreExactThreeFieldsWithoutOwnerOrTarget() {
        String valid="{\"admissionId\":\"7feb99a1-1801-46b1-9bba-d8254406de99\",\"admissionSha256\":\""+"a".repeat(64)+"\",\"parametersSha256\":\""+"b".repeat(64)+"\"}";
        assertDoesNotThrow(()->PublishedKeyAdmissionService.Reference.parse(raw(valid)));
        for(String value:List.of(valid.replace("\"admissionId\":","\"owner\":{},\"admissionId\":"),valid+"{}",valid.replace("7feb99a1","7FEB99A1"),valid.replace("\"parametersSha256\":","\"parametersSha256\":null,\"parametersSha256\":")))assertThrows(IllegalArgumentException.class,()->PublishedKeyAdmissionService.Reference.parse(raw(value)));
        assertThrows(IllegalArgumentException.class,()->PublishedKeyAdmissionService.Reference.parse(raw(valid+" ".repeat(4096))));
    }
    @Test void publishedAssetTypesAndReferencesMatchTheFrozenMetadataCodec() {
        var valid=new LinkedHashMap<String,Object>();valid.put("id",11L);valid.put("assetCode","orders_api");valid.put("version","v1");valid.put("targetType","table");valid.put("targetRef","13");assertDoesNotThrow(()->PublishedKeyAdmissionService.asset(valid));
        for(String value:List.of("+13","0013")){var compatible=new LinkedHashMap<>(valid);compatible.put("targetRef",value);assertEquals(value,PublishedKeyAdmissionService.asset(compatible).get("targetRef"));}
        for(Object value:List.of("0","-1","9223372036854775808",false,13L)){var invalid=new LinkedHashMap<>(valid);invalid.put("targetRef",value);assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->PublishedKeyAdmissionService.asset(invalid)).getStatusCode().value());}
    }
}
