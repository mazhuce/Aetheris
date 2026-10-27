package com.aetheris.workflow.fixedexecution;

import com.aetheris.security.protocol.execution.WorkflowQualityParent;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.*;

/** Child admission is posted once; uncertain delivery only queries the same sealed parent. */
@Component
public class WorkflowQualityClient {
    public static final String BASE="/internal/v2/workflow-children/";
    private final SignedHttpClient transport;
    public WorkflowQualityClient(@Qualifier("workflowQualityTransport") SignedHttpClient transport){this.transport=Objects.requireNonNull(transport);}
    public record Definition(long ruleId,long executionRevision,String definitionSha256){}
    public record Result(long resultId,long ruleId,String manifestSha256,Long metadataReceiptId,String state,
                         Long violationCount,long thresholdValue,Long durationMs,String finishedAt) {
        public boolean terminal(){return Set.of("passed","failed","error").contains(state);}
    }
    public Definition approvedDefinition(long id){
        var response=send(new SignedHttpClient.Request("quality","POST",BASE+"definition","quality.workflow-child.definition",FixedJson.bytes(Map.of("ruleId",id))));
        if(response.status()!=200)throw unavailable();var n=FixedJson.parse(response.body(),65536);FixedJson.fields(n,"ruleId","executionRevision","definitionSha256","approved","status");
        if(FixedJson.number(n,"ruleId",1)!=id||!n.path("approved").isBoolean()||!n.get("approved").booleanValue()||!"enabled".equals(FixedJson.text(n,"status")))throw new IllegalStateException("质量规则当前未启用或未批准");
        return new Definition(id,FixedJson.number(n,"executionRevision",1),FixedJson.hash(n,"definitionSha256"));
    }
    public Result submit(WorkflowQualityParent.Reference ref){return request("submit",WorkflowQualityParent.OPERATION,ref,true);}
    public Result status(WorkflowQualityParent.Reference ref){return request("status","quality.workflow-child.status",ref,false);}
    private Result request(String path,String operation,WorkflowQualityParent.Reference ref,boolean submit){
        var response=send(new SignedHttpClient.Request("quality","POST",BASE+path,operation,FixedJson.bytes(ref)));
        if(!submit&&response.status()==404)return null;
        if(response.status()!=(submit?202:200))throw unavailable();
        var n=FixedJson.parse(response.body(),32768);FixedJson.fields(n,"resultId","ruleId","manifestSha256","metadataReceiptId","parent","state","violationCount","thresholdValue","durationMs","finishedAt");
        if(!FixedJson.parse(FixedJson.bytes(ref),65536).equals(n.get("parent")))throw unavailable();
        String state=FixedJson.text(n,"state");if(!Set.of("running","unknown","passed","failed","error").contains(state))throw unavailable();
        Long receipt=nullable(n,"metadataReceiptId",1),violations=nullable(n,"violationCount",0),duration=nullable(n,"durationMs",0);long threshold=FixedJson.number(n,"thresholdValue",0);
        var finished=n.get("finishedAt");String at=finished.isNull()?null:FixedJson.text(n,"finishedAt");if(at!=null)OffsetDateTime.parse(at);
        if(Set.of("passed","failed","error").contains(state)&&(receipt==null||at==null))throw unavailable();
        if(Set.of("passed","failed").contains(state)&&(receipt==null||violations==null||duration==null||at==null||state.equals("passed")!=(violations<=threshold)))throw unavailable();
        if(Set.of("running","unknown","error").contains(state)&&violations!=null)throw unavailable();
        return new Result(FixedJson.number(n,"resultId",1),FixedJson.number(n,"ruleId",1),FixedJson.hash(n,"manifestSha256"),receipt,state,violations,threshold,duration,at);
    }
    private static Long nullable(JsonNode n,String key,long min){return n.get(key).isNull()?null:FixedJson.number(n,key,min);}
    private SignedHttpClient.Response send(SignedHttpClient.Request request){try{return transport.send(request);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}}
    private static IllegalStateException unavailable(){return new IllegalStateException("质量子任务结果未可靠确认，禁止自动重放");}
}
