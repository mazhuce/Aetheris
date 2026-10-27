package com.aetheris.security.protocol.execution;

import com.aetheris.security.protocol.BodyHash;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowQualityParentTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode manifest(){
        var d=json.createObjectNode().put("schemaVersion",1).put("taskType","quality").put("qualityRuleId",7).put("qualityExecutionRevision",3).put("qualityDefinitionSha256","a".repeat(64));
        for(String key:List.of("datasourceId","sql","timeoutSeconds","maxRetries","targetTable","jobDefinitionId","flinkEndpoint","flinkMode","eventTimeColumn","watermarkExpression","allowedLatenessSeconds","lateDataPolicy","deadLetterTable"))d.putNull(key);
        d.set("dependencies",json.createObjectNode());
        d.put("allowedLatenessSeconds",0).put("lateDataPolicy","drop");
        var a=json.createObjectNode().put("kind","manual").put("uid",2).put("username","测试人员").put("accessVersion",4).put("requestId","parent-123");
        var p=json.createObjectNode().put("uid",3).put("username","批准人员").put("accessVersion",5).put("revision",8).put("definitionSha256",WorkflowQualityParent.definitionHash(d));
        var n=json.createObjectNode().put("schemaVersion",1).put("origin","workflow").put("operation",WorkflowQualityParent.OPERATION).put("resourceId",11).put("runId",12).put("attemptId",13).put("fence",2).put("executionRevision",8).put("definitionSha256",WorkflowQualityParent.definitionHash(d));
        n.set("actor",a);n.set("approval",p);n.set("execution",d);n.set("parameters",json.createObjectNode().put("bizDate","2024-02-29"));n.putNull("parent");return n;
    }
    WorkflowQualityParent parse(ObjectNode n){String raw=n.toString(),hash=BodyHash.sha256(raw.getBytes(StandardCharsets.UTF_8));return WorkflowQualityParent.parse(raw,hash,new WorkflowQualityParent.Reference(12,13,2,8,hash));}
    @Test void originalActorBecomesChildWithoutChangingUidAvOrRequestId(){for(String kind:List.of("manual","cron","pipeline")){var n=manifest();((ObjectNode)n.get("actor")).put("kind",kind);var parent=parse(n);assertEquals(kind,parent.actor().get("kind").asText());assertEquals("workflow-child",parent.childActor().get("kind").asText());assertEquals(4,parent.childActor().get("accessVersion").asLong());assertEquals("parent-123",parent.childActor().get("requestId").asText());assertEquals(3,parent.qualityExecutionRevision());assertEquals("a".repeat(64),parent.qualityDefinitionSha256());parent.actor().put("uid",100);assertEquals(2,parent.actor().get("uid").asLong());}}
    @Test void digestAndEveryParentTupleFieldMustMatch(){var n=manifest();String raw=n.toString(),hash=BodyHash.sha256(raw.getBytes(StandardCharsets.UTF_8));for(var ref:List.of(new WorkflowQualityParent.Reference(99,13,2,8,hash),new WorkflowQualityParent.Reference(12,99,2,8,hash),new WorkflowQualityParent.Reference(12,13,9,8,hash),new WorkflowQualityParent.Reference(12,13,2,9,hash),new WorkflowQualityParent.Reference(12,13,2,8,"b".repeat(64))))assertThrows(IllegalArgumentException.class,()->WorkflowQualityParent.parse(raw,hash,ref));}
    @Test void dateAndNestedDefinitionHaveStrictShape(){for(String date:List.of("0000-01-01","2025-02-29","2024-2-29","2024-02-29 ")){var n=manifest();((ObjectNode)n.get("parameters")).put("bizDate",date);assertThrows(IllegalArgumentException.class,()->parse(n));}var n=manifest();((ObjectNode)n.get("execution")).put("qualityExecutionRevision",4);assertThrows(IllegalArgumentException.class,()->parse(n));var bad=manifest();((ObjectNode)bad.get("execution")).put("callerChosenSql","SELECT 1");assertThrows(IllegalArgumentException.class,()->parse(bad));}
    @Test void childActorAndUnsafeUsernameCannotBePromotedToParent(){for(String name:List.of("bad name","bad|name","x\u200by","x\n")){var n=manifest();((ObjectNode)n.get("actor")).put("username",name);assertThrows(IllegalArgumentException.class,()->parse(n));}var n=manifest();((ObjectNode)n.get("actor")).put("kind","workflow-child");assertThrows(IllegalArgumentException.class,()->parse(n));}
    @Test void referencesRejectUnknownDuplicateWrongTypesAndOverflow(){for(String raw:List.of("{\"instanceId\":1,\"attemptId\":2,\"fence\":1,\"executionRevision\":1,\"manifestSha256\":\""+"a".repeat(64)+"\",\"extra\":1}","{\"instanceId\":1,\"instanceId\":2}","{\"instanceId\":9223372036854775808}","{} trailing"))assertThrows(IllegalArgumentException.class,()->WorkflowQualityParent.Reference.parse(raw.getBytes(StandardCharsets.UTF_8)));}
    @Test void jsonbDefinitionHashUsesDatabaseOrderSpacingAndChineseBytes() throws Exception {var n=json.readTree("{\"longKey\":null,\"x\":\"中文\\n\",\"ids\":[1,2]}");assertEquals(BodyHash.sha256("{\"x\": \"中文\\n\", \"ids\": [1, 2], \"longKey\": null}".getBytes(StandardCharsets.UTF_8)),WorkflowQualityParent.definitionHash(n));}
}
