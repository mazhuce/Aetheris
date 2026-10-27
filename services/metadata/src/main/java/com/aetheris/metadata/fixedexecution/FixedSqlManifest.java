package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.service.SqlWriteGuard;
import com.aetheris.security.protocol.BodyHash;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

/** Compiles only the exact v28 DB projection. No payload SQL, user proof or URI can select execution. */
public final class FixedSqlManifest {
    private final String canonical;private final FixedTransferManifest.Binding binding;
    private final FixedTransferManifest.Actor actor;private final FixedTransferManifest.Approval approval;private final Definition definition;
    public record Definition(long datasourceId,String sql,int timeoutSeconds){}
    private FixedSqlManifest(String canonical,FixedTransferManifest.Binding binding,FixedTransferManifest.Actor actor,FixedTransferManifest.Approval approval,Definition definition){this.canonical=canonical;this.binding=binding;this.actor=actor;this.approval=approval;this.definition=definition;}
    public String canonical(){return canonical;}public String sha256(){return binding.manifestSha256();}public FixedTransferManifest.Binding binding(){return binding;}public Definition definition(){return definition;}
    public static FixedSqlManifest parse(String canonical,String expectedHash,FixedExecutionAdapter.Submission expected){
        FixedExecutionOperation.SQL.validate(expected);require(canonical!=null&&canonical.getBytes(StandardCharsets.UTF_8).length<=768*1024&&BodyHash.sha256(canonical.getBytes(StandardCharsets.UTF_8)).equals(expectedHash)&&expected.manifestSha256().equals(expectedHash));
        var n=FixedJson.parse(canonical);fields(n,"schemaVersion","origin","operation","resourceId","runId","attemptId","fence","executionRevision","definitionSha256","actor","approval","execution","parameters","parent");
        require(number(n,"schemaVersion")==1&&"workflow".equals(text(n,"origin",16))&&FixedExecutionOperation.SQL.operation().equals(text(n,"operation",64)));
        long resource=number(n,"resourceId"),revision=number(n,"executionRevision");require(resource>0&&number(n,"runId")==expected.runId()&&number(n,"attemptId")==expected.attemptId()&&number(n,"fence")==expected.fence()&&revision==expected.executionRevision());
        require(n.get("parent").isNull());var parameters=n.get("parameters");fields(parameters,"bizDate");String date=text(parameters,"bizDate",10);require(date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        try{var parsed=LocalDate.parse(date);require(parsed.getYear()>0&&parsed.toString().equals(date));}catch(DateTimeException bad){throw invalid();}
        var a=n.get("actor");fields(a,"kind","uid","username","accessVersion","requestId");String kind=text(a,"kind",32),name=text(a,"username",64),request=text(a,"requestId",128);username(name);
        require(Set.of("manual","cron","pipeline").contains(kind)&&number(a,"uid")>0&&number(a,"accessVersion")>=0&&request.matches("[A-Za-z0-9._:-]{1,128}"));
        var actor=new FixedTransferManifest.Actor(kind,number(a,"uid"),name,number(a,"accessVersion"),request);
        var p=n.get("approval");fields(p,"uid","username","accessVersion","revision","definitionSha256");String approver=text(p,"username",64);username(approver);String definitionHash=hash(p,"definitionSha256");
        require(number(p,"uid")>0&&number(p,"accessVersion")>=0&&number(p,"revision")==revision&&definitionHash.equals(hash(n,"definitionSha256")));
        var approval=new FixedTransferManifest.Approval(number(p,"uid"),approver,number(p,"accessVersion"),revision,definitionHash);
        var d=n.get("execution");fields(d,"schemaVersion","taskType","datasourceId","sql","timeoutSeconds","maxRetries","targetTable","jobDefinitionId","qualityRuleId","flinkEndpoint","flinkMode","eventTimeColumn","watermarkExpression","allowedLatenessSeconds","lateDataPolicy","deadLetterTable","dependencies");
        require(number(d,"schemaVersion")==1&&"sql".equals(text(d,"taskType",16))&&number(d,"datasourceId")>0);
        long timeoutValue=d.get("timeoutSeconds").isNull()?1800:number(d,"timeoutSeconds");require(timeoutValue>=1&&timeoutValue<=86400);int timeout=(int)timeoutValue;
        if(!d.get("maxRetries").isNull())require(number(d,"maxRetries")>=0&&number(d,"maxRetries")<=10);
        for(String field:List.of("targetTable","flinkEndpoint","flinkMode","eventTimeColumn","watermarkExpression","lateDataPolicy","deadLetterTable"))if(!d.get(field).isNull())text(d,field,2048);
        for(String field:List.of("jobDefinitionId","qualityRuleId"))if(!d.get(field).isNull())require(number(d,field)>0);
        if(!d.get("allowedLatenessSeconds").isNull())require(number(d,"allowedLatenessSeconds")>=0&&number(d,"allowedLatenessSeconds")<=Integer.MAX_VALUE);
        var dependencies=d.get("dependencies");require(dependencies.isObject());if(!dependencies.isEmpty()){
            fields(dependencies,"upstreamTaskIds");var ids=dependencies.get("upstreamTaskIds");require(ids.isArray());long last=0;for(var id:ids){require(id.isIntegralNumber()&&id.canConvertToLong()&&id.longValue()>last);last=id.longValue();}
        }
        require(definitionHash.equals(FixedSqlJsonb.hash(d)));String original=text(d,"sql",512*1024);require(!original.isBlank());String sql=original.replace("${bizDate}",date);require(!sql.contains("${"));SqlWriteGuard.validate(sql);
        return new FixedSqlManifest(canonical,new FixedTransferManifest.Binding("workflow",FixedExecutionOperation.SQL.operation(),resource,expected.runId(),expected.attemptId(),expected.fence(),revision,expectedHash),actor,approval,new Definition(number(d,"datasourceId"),sql,timeout));
    }
    public Map<String,Object> authorizationRequest(){var body=new LinkedHashMap<String,Object>();body.put("schemaVersion",1);body.put("binding",binding);body.put("actor",actor);body.put("approval",approval);body.put("targets",Map.of("sourceDatasourceId",definition.datasourceId(),"targetDatasourceId",definition.datasourceId()));body.put("parent",null);return Collections.unmodifiableMap(body);}
}
