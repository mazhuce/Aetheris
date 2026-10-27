package com.aetheris.quality.service;

import com.aetheris.security.protocol.execution.WorkflowQualityParent;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowChildResultSerializationTest {
    @Test void pendingChildKeepsAllNullableWireFieldsUnderProductionNonNullConfiguration() throws Exception {
        var mapper=new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var result=new WorkflowChildQualityService.Result(1,2,"a".repeat(64),null,new WorkflowQualityParent.Reference(3,4,1,1,"b".repeat(64)),"running",null,0,null,null);
        var wire=mapper.readTree(mapper.writeValueAsBytes(result));assertEquals(10,wire.size());
        for(String key:java.util.List.of("metadataReceiptId","violationCount","durationMs","finishedAt"))assertTrue(wire.has(key)&&wire.get(key).isNull(),key);
    }
}
