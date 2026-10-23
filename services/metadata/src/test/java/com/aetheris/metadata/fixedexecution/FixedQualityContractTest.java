package com.aetheris.metadata.fixedexecution;

import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

class FixedQualityContractTest {
    @Test void frozenFiveVectorsMatchExactlyAndRemainBoundToTheirSourceFixture()throws Exception{
        byte[] raw;try(var in=getClass().getResourceAsStream("/fixed-quality-generator-v1.json")){assertNotNull(in);raw=in.readAllBytes();}
        assertEquals("219806b6dc82b955f8ac3c031de413774c28ee5e905f07ce4e07d883bc1e5fca",com.aetheris.security.protocol.BodyHash.sha256(raw));
        var n=parse(raw);assertEquals(5,n.get("vectors").size());
        for(var v:n.get("vectors"))assertEquals(v.get("sql").asText(),MetadataQualitySqlGeneratorV1.generate(v.get("targetTable").asText(),v.get("ruleType").asText(),v.get("expression").asText()));
        for(String expression:List.of("load_file('secret') IS NOT NULL","x IN (SELECT x FROM other)","sleep(1)=0","x>0; SELECT 1","x>0 -- comment"))assertThrows(IllegalArgumentException.class,()->MetadataQualitySqlGeneratorV1.generate("business.input","not_null",expression));
    }
    static Map<String,Object> jdbc(Object value,String label){var n=new LinkedHashMap<String,Object>();n.put("columns",List.of(label));n.put("rows",List.of(Arrays.asList(value)));n.put("rowCount",1);n.put("truncated",false);return n;}
    @Test void countIsAnExactNonnegativeLongAndPgNumericSumKeepsItsIntegerShape(){
        for(Object value:List.of(0,3L,new BigInteger("9223372036854775807"),new BigDecimal("2")))assertEquals(((Number)value).longValue(),FixedQualityResult.fromJdbc(jdbc(value,"violation_count"),"duplicate",1).violationCount());
        for(Object value:Arrays.asList(null,"1",true,1.0,1.5,new BigDecimal("1.0"),new BigDecimal("1.5"),-1,new BigInteger("9223372036854775808"),Double.NaN,Double.POSITIVE_INFINITY))assertThrows(IllegalArgumentException.class,()->FixedQualityResult.fromJdbc(jdbc(value,"violation_count"),"not_null",1));
    }
    @Test void scalarCustomSqlAllowsItsOneUnaliasedColumnButNeverIgnoresExtraShape(){
        assertEquals(2,FixedQualityResult.fromJdbc(jdbc(2L,"count"),"sql",0).violationCount());
        for(String label:List.of("VIOLATION_COUNT","Violation_Count"))assertEquals(2,FixedQualityResult.fromJdbc(jdbc(2L,label),"not_null",0).violationCount());
        for(String label:List.of("violation_count_extra","violationcount"," violation_count "))assertThrows(IllegalArgumentException.class,()->FixedQualityResult.fromJdbc(jdbc(2L,label),"not_null",0));
        assertThrows(IllegalArgumentException.class,()->FixedQualityResult.fromJdbc(jdbc(2L,"count"),"not_null",0));
        var empty=jdbc(1L,"count");empty.put("rows",List.of());assertThrows(IllegalArgumentException.class,()->FixedQualityResult.fromJdbc(empty,"sql",0));
        var multi=jdbc(1L,"count");multi.put("rows",List.of(List.of(1L),List.of(2L)));multi.put("rowCount",2);assertThrows(IllegalArgumentException.class,()->FixedQualityResult.fromJdbc(multi,"sql",0));
        var columns=jdbc(1L,"count");columns.put("columns",List.of("count","extra"));columns.put("rows",List.of(List.of(1L,2L)));assertThrows(IllegalArgumentException.class,()->FixedQualityResult.fromJdbc(columns,"sql",0));
        var mismatch=jdbc(1L,"count");mismatch.put("rows",List.of(List.of(1L,2L)));assertThrows(IllegalArgumentException.class,()->FixedQualityResult.fromJdbc(mismatch,"sql",0));
        var truncated=jdbc(1L,"count");truncated.put("truncated",true);assertThrows(IllegalArgumentException.class,()->FixedQualityResult.fromJdbc(truncated,"sql",0));
        var map=jdbc(1L,"count");map.put("rows",List.of(Map.of("count",1L)));assertThrows(IllegalArgumentException.class,()->FixedQualityResult.fromJdbc(map,"sql",0));
    }
    @Test void storedResultCodecsCannotBeSwappedBetweenOperations(){
        assertInstanceOf(FixedQualityResult.class,FixedExecutionOperation.QUALITY.result("{\"violationCount\":0,\"durationMs\":1}"));
        for(String bad:List.of("{\"violationCount\":0.0,\"durationMs\":1}","{\"violationCount\":\"0\",\"durationMs\":1}","{\"violationCount\":-1,\"durationMs\":1}","{\"violationCount\":9223372036854775808,\"durationMs\":1}","{\"rowsRead\":0,\"rowsWritten\":0,\"durationMs\":1}"))assertThrows(IllegalArgumentException.class,()->FixedExecutionOperation.QUALITY.result(bad));
        assertThrows(IllegalArgumentException.class,()->FixedExecutionOperation.TRANSFER.result("{\"violationCount\":0,\"durationMs\":1}"));
    }
}
