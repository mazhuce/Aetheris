package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.service.SqlSafetyPolicy;
import com.aetheris.security.protocol.BodyHash;
import com.aetheris.security.protocol.execution.WorkflowQualityParent;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

/** SchemaVersion1 compiles the DB-sealed six-field definition, never a caller SQL copy. */
public final class FixedQualityManifest {
    private final String canonical,hash;private final FixedTransferManifest.Binding binding;
    private final FixedTransferManifest.Actor actor;private final FixedTransferManifest.Approval approval;private final Definition definition;private final JsonNode parent;
    public record Definition(long datasourceId,String targetTable,String ruleType,String expression,int threshold,String sql){ }
    private FixedQualityManifest(String canonical,String hash,FixedTransferManifest.Binding binding,FixedTransferManifest.Actor actor,FixedTransferManifest.Approval approval,Definition definition,JsonNode parent){this.canonical=canonical;this.hash=hash;this.binding=binding;this.actor=actor;this.approval=approval;this.definition=definition;this.parent=parent==null?null:parent.deepCopy();}
    public String canonical(){return canonical;}public String sha256(){return hash;}public FixedTransferManifest.Binding binding(){return binding;}public Definition definition(){return definition;}
    public FixedTransferManifest.Actor actor(){return actor;}public FixedTransferManifest.Approval approval(){return approval;}
    public boolean workflowChild(){return parent!=null;}public JsonNode parent(){return parent==null?null:parent.deepCopy();}
    public WorkflowQualityParent.Reference parentReference(){require(workflowChild());return new WorkflowQualityParent.Reference(number(parent,"runId"),number(parent,"attemptId"),number(parent,"fence"),number(parent.get("approval"),"revision"),hash(parent,"manifestSha256"));}
    public static FixedQualityManifest parse(String canonical,String expectedHash,FixedExecutionAdapter.Submission expected){
        FixedExecutionOperation.QUALITY.validate(expected);
        require(canonical!=null&&canonical.getBytes(StandardCharsets.UTF_8).length<=768*1024&&BodyHash.sha256(canonical.getBytes(StandardCharsets.UTF_8)).equals(expectedHash)&&expected.manifestSha256().equals(expectedHash));
        var n=FixedJson.parse(canonical);fields(n,"schemaVersion","origin","operation","resourceId","runId","attemptId","fence","executionRevision","definitionSha256","actor","approval","execution","parameters","parent");
        require(number(n,"schemaVersion")==1&&"quality".equals(text(n,"origin",16))&&FixedExecutionOperation.QUALITY.operation().equals(text(n,"operation",64)));
        long resource=number(n,"resourceId"),revision=number(n,"executionRevision");require(resource>0&&number(n,"runId")==expected.runId()&&number(n,"attemptId")==0&&number(n,"fence")==1&&revision==expected.executionRevision());
        fields(n.get("parameters"));
        var a=n.get("actor");fields(a,"kind","uid","username","accessVersion","requestId");String kind=text(a,"kind",32),name=text(a,"username",64),request=text(a,"requestId",128);username(name);
        require(Set.of("manual","cron","workflow-child").contains(kind)&&number(a,"uid")>0&&number(a,"accessVersion")>=0&&request.matches("[A-Za-z0-9._:-]{1,128}"));
        var actor=new FixedTransferManifest.Actor(kind,number(a,"uid"),name,number(a,"accessVersion"),request);
        JsonNode parent=null;
        if("workflow-child".equals(kind)){
            parent=n.get("parent");fields(parent,"resourceId","runId","attemptId","fence","manifestSha256","approval");
            for(String field:List.of("resourceId","runId","attemptId","fence"))require(number(parent,field)>0);hash(parent,"manifestSha256");
            var parentApproval=parent.get("approval");fields(parentApproval,"uid","username","accessVersion","revision","definitionSha256");
            username(text(parentApproval,"username",64));require(number(parentApproval,"uid")>0&&number(parentApproval,"accessVersion")>=0&&number(parentApproval,"revision")>0);hash(parentApproval,"definitionSha256");
        }else require(n.get("parent").isNull());
        var p=n.get("approval");fields(p,"uid","username","accessVersion","revision","definitionSha256");String approver=text(p,"username",64);username(approver);String definitionHash=hash(p,"definitionSha256");
        require(number(p,"uid")>0&&number(p,"accessVersion")>=0&&number(p,"revision")==revision&&definitionHash.equals(hash(n,"definitionSha256")));
        var approval=new FixedTransferManifest.Approval(number(p,"uid"),approver,number(p,"accessVersion"),revision,definitionHash);
        var d=n.get("execution");fields(d,"schemaVersion","datasourceId","targetTable","ruleType","expression","threshold");require(number(d,"schemaVersion")==1&&number(d,"datasourceId")>0&&definitionHash.equals(FixedTransferManifest.definitionHash(d)));
        String table=text(d,"targetTable",128),type=text(d,"ruleType",32).toLowerCase(Locale.ROOT),expression=text(d,"expression",200000);
        long threshold=d.get("threshold").isNull()?0:number(d,"threshold");require(threshold>=0&&threshold<=Integer.MAX_VALUE);
        String sql=MetadataQualitySqlGeneratorV1.generate(table,type,expression);SqlSafetyPolicy.readOnly(sql);
        var definition=new Definition(number(d,"datasourceId"),table,type,expression,(int)threshold,sql);
        return new FixedQualityManifest(canonical,expectedHash,new FixedTransferManifest.Binding("quality",FixedExecutionOperation.QUALITY.operation(),resource,expected.runId(),0,1,revision,expectedHash),actor,approval,definition,parent);
    }
    public Map<String,Object> authorizationRequest(){
        var targets=new LinkedHashMap<String,Object>();targets.put("sourceDatasourceId",definition.datasourceId());targets.put("targetDatasourceId",null);
        var body=new LinkedHashMap<String,Object>();body.put("schemaVersion",1);body.put("binding",binding);body.put("actor",actor);body.put("approval",approval);body.put("targets",Collections.unmodifiableMap(targets));body.put("parent",parent());return Collections.unmodifiableMap(body);
    }
}
