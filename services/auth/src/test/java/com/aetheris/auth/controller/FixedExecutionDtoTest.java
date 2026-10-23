package com.aetheris.auth.controller;

import com.aetheris.auth.fixedexecution.FixedExecutionRequest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FixedExecutionDtoTest {
    static final ObjectMapper JSON=new ObjectMapper();
    ObjectNode fixture(boolean child) throws Exception {
        var approval=new FixedExecutionRequest.Approval(3,"approver",0,2,"a".repeat(64));
        var n=new FixedExecutionRequest(1,new FixedExecutionRequest.Binding(child?"quality":"workflow",
                child?"metadata.fixed-quality-query.submit":"metadata.fixed-sql.submit",7,11,child?0:13,1,2,"b".repeat(64)),
                new FixedExecutionRequest.Actor(child?"workflow-child":"manual",2,"operator",0,"request-123"),approval,
                new FixedExecutionRequest.Targets(5,child?null:5L),child?new FixedExecutionRequest.Parent(9,20,21,2,"c".repeat(64),approval):null);
        return JSON.valueToTree(n);
    }
    FixedExecutionRequest parse(ObjectNode n){return FixedExecutionRequest.parse(n.toString().getBytes(StandardCharsets.UTF_8));}
    List<String> paths(ObjectNode n,String prefix) {
        List<String> paths=new ArrayList<>();n.fields().forEachRemaining(item->{String path=prefix+item.getKey();paths.add(path);
            if(item.getValue() instanceof ObjectNode nested)paths.addAll(paths(nested,path+"."));});return paths;
    }
    ObjectNode parent(ObjectNode n,String path){int split=path.lastIndexOf('.');return split<0?n:(ObjectNode)n.at("/"+path.substring(0,split).replace('.','/'));}
    String field(String path){return path.substring(path.lastIndexOf('.')+1);}
    @Test void everyFieldIsMandatoryAtEveryLevelIncludingExplicitNullableFields() throws Exception {
        for(boolean child:List.of(false,true))for(String path:paths(fixture(child),"")) {
            ObjectNode missing=fixture(child);parent(missing,path).remove(field(path));assertThrows(IllegalArgumentException.class,()->parse(missing),path);
            if(path.equals("parent")&&!child)continue;
            if(path.equals("targets.targetDatasourceId")&&child)continue;
            ObjectNode invalid=fixture(child);parent(invalid,path).putNull(field(path));
            assertThrows(IllegalArgumentException.class,()->parse(invalid),path);
        }
    }
    @Test void everyFiniteIntegerAndTextFieldRejectsCoercionOverflowAndWrongShape() throws Exception {
        var base=fixture(true);
        for(String path:paths(base,"")) {
            JsonNode value=base.at("/"+path.replace('.','/'));
            List<JsonNode> bad=value.isNumber()?List.of(TextNode.valueOf("1"),BooleanNode.TRUE,DoubleNode.valueOf(1.0),
                    BigIntegerNode.valueOf(new BigInteger("9223372036854775808")),LongNode.valueOf(-1))
                    :value.isTextual()?List.of(BooleanNode.TRUE,LongNode.valueOf(1),JSON.createArrayNode()):List.of();
            for(JsonNode replacement:bad) {
                ObjectNode n=base.deepCopy();parent(n,path).set(field(path),replacement);
                assertThrows(IllegalArgumentException.class,()->parse(n),path);
            }
        }
    }
    @Test void manualCronAndPipelineKindsStayTypedAndNullabilityIsOperationSpecific() throws Exception {
        for(String kind:List.of("manual","cron","pipeline")) {
            var n=fixture(false);((ObjectNode)n.get("actor")).put("kind",kind);
            assertEquals(kind,parse(n).actor().kind());
        }
        var n=fixture(true);assertNull(parse(n).targets().targetDatasourceId());assertNotNull(parse(n).parent());
        for(String path:List.of("","binding","actor","approval","targets","parent","parent.approval")) {
            n=fixture(true);ObjectNode object=path.isEmpty()?n:(ObjectNode)n.at("/"+path.replace('.','/'));
            object.put("action","admin");ObjectNode invalid=n;assertThrows(IllegalArgumentException.class,()->parse(invalid));
        }
    }
}
