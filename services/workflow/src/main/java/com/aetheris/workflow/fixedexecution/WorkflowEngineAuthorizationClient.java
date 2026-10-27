package com.aetheris.workflow.fixedexecution;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.*;

/** Uses only persisted original tuples. It cannot mint or relay a replacement USER proof. */
@Component
public final class WorkflowEngineAuthorizationClient {
    public static final String OPERATION="auth.engine-execution.authorize";
    public static final String PATH="/internal/v2/permissions/engine-execution-authorize";
    private final SignedHttpClient transport;
    public WorkflowEngineAuthorizationClient(@Qualifier("workflowAuthTransport") SignedHttpClient transport){this.transport=Objects.requireNonNull(transport);}
    public boolean authorize(JsonNode manifest,String manifestHash,String engineHash){
        try{
            FixedJson.fields(manifest,"schemaVersion","origin","operation","resourceId","runId","attemptId","fence","executionRevision","definitionSha256","actor","approval","execution","parameters","parent");
            String engine=FixedJson.text(manifest.get("execution"),"taskType");
            if(FixedJson.number(manifest,"schemaVersion",1)!=1||!Set.of("spark","flink").contains(engine)||!"workflow".equals(FixedJson.text(manifest,"origin"))
                    ||!("workflow.fixed-"+engine+".dispatch").equals(FixedJson.text(manifest,"operation"))||!manifest.get("parent").isNull()
                    ||manifestHash==null||!manifestHash.matches("[a-f0-9]{64}")||engineHash==null||!engineHash.matches("[a-f0-9]{64}"))throw unavailable();
            var actor=manifest.get("actor");var approval=manifest.get("approval");
            FixedJson.fields(actor,"kind","uid","username","accessVersion","requestId");
            FixedJson.fields(approval,"uid","username","accessVersion","revision","definitionSha256");
            FixedJson.number(actor,"uid",1);FixedJson.number(actor,"accessVersion",0);FixedJson.number(approval,"uid",1);FixedJson.number(approval,"accessVersion",0);
            if(FixedJson.text(actor,"username").isBlank()||FixedJson.text(approval,"username").isBlank()||FixedJson.text(actor,"requestId").isBlank())throw unavailable();
            if(!Set.of("manual","cron","pipeline").contains(FixedJson.text(actor,"kind"))
                    ||FixedJson.number(approval,"revision",1)!=FixedJson.number(manifest,"executionRevision",1)
                    ||!FixedJson.hash(approval,"definitionSha256").equals(FixedJson.hash(manifest,"definitionSha256")))throw unavailable();
            long task=FixedJson.number(manifest,"resourceId",1);
            ObjectNode binding=FixedJson.JSON.createObjectNode().put("engine",engine).put("taskId",task)
                .put("instanceId",FixedJson.number(manifest,"runId",1)).put("attemptId",FixedJson.number(manifest,"attemptId",1))
                .put("fence",FixedJson.number(manifest,"fence",1)).put("executionRevision",FixedJson.number(manifest,"executionRevision",1))
                .put("manifestSha256",manifestHash).put("definitionSha256",FixedJson.hash(manifest,"definitionSha256"))
                .put("engineDefinitionSha256",engineHash);
            ObjectNode input=FixedJson.JSON.createObjectNode().put("schemaVersion",1);input.set("binding",binding);input.set("actor",actor);input.set("approval",approval);
            var response=transport.send(new SignedHttpClient.Request("auth","POST",PATH,OPERATION,FixedJson.bytes(input)));
            if(response.status()!=200)throw unavailable();
            JsonNode result=FixedJson.parse(response.body(),16*1024);FixedJson.fields(result,"binding","allowed","reason","checks");
            if(!FixedJson.parse(FixedJson.bytes(binding),16*1024).equals(result.get("binding"))||!result.get("allowed").isBoolean()
                    ||!result.get("checks").isArray())throw unavailable();
            boolean allowed=result.get("allowed").booleanValue();String reason=result.get("reason").isNull()?null:FixedJson.text(result,"reason");
            if(allowed?reason!=null:!Set.of("SUBJECT_INACTIVE","PERMISSION_DENIED").contains(reason))throw unavailable();
            var checks=result.get("checks");if(!allowed&&"SUBJECT_INACTIVE".equals(reason)&&checks.isEmpty())return false;
            if(checks.size()!=3)throw unavailable();
            String[][] expected={{"actor","workflow_task","execute"},{"approval","workflow_task","update"},{"approval","platform","manage"}};
            boolean all=true;
            for(int i=0;i<3;i++){
                var check=checks.get(i);FixedJson.fields(check,"subject","resourceType","resourceId","action","allowed");
                if(!expected[i][0].equals(FixedJson.text(check,"subject"))||!expected[i][1].equals(FixedJson.text(check,"resourceType"))
                        ||!expected[i][2].equals(FixedJson.text(check,"action"))||!check.get("allowed").isBoolean()
                        ||(i==2?!check.get("resourceId").isNull():FixedJson.number(check,"resourceId",1)!=task))throw unavailable();
                all&=check.get("allowed").booleanValue();
            }
            if(allowed!=all)throw unavailable();return allowed;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(RuntimeException failure){throw unavailable();}
    }
    public static IllegalStateException unavailable(){return new IllegalStateException("引擎执行授权未可靠确认，未获新派发许可");}
}
