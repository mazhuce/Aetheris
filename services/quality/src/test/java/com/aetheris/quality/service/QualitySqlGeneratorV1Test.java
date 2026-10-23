package com.aetheris.quality.service;
import com.aetheris.quality.fixedexecution.FixedJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QualitySqlGeneratorV1Test {
 @Test void frozenFiveRuleVectorsMatchExactSql() throws Exception {try(var input=getClass().getResourceAsStream("/fixed-quality-generator-v1.json")){var root=FixedJson.parse(input.readAllBytes(),65536);assertEquals(1,root.path("schemaVersion").asInt());assertEquals(5,root.path("vectors").size());for(var v:root.get("vectors"))assertEquals(v.get("sql").asText(),QualitySqlGeneratorV1.generate(v.get("targetTable").asText(),v.get("ruleType").asText(),v.get("expression").asText()));}}
 @Test void invalidIdentifiersUnknownTypesAndHiddenWritesNeverGenerateSql(){for(String target:new String[]{"a;drop table b","a--b","a b","a..b"})assertThrows(IllegalArgumentException.class,()->QualitySqlGeneratorV1.generate(target,"range","x > 0"));assertThrows(IllegalArgumentException.class,()->QualitySqlGeneratorV1.generate("ods.orders","future","x > 0"));}
}
