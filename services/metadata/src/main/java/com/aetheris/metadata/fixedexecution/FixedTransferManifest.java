package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.service.SqlSafetyPolicy;
import com.aetheris.security.protocol.BodyHash;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

/** First fixed execution adapter: a source-sealed ingestion definition, never caller-provided SQL. */
public final class FixedTransferManifest {
    private final String canonical,sha256;
    private final Binding binding;
    private final Actor actor;
    private final Approval approval;
    private final Definition definition;
    private FixedTransferManifest(String canonical,String sha256,Binding binding,Actor actor,Approval approval,Definition definition) {
        this.canonical=canonical;this.sha256=sha256;this.binding=binding;this.actor=actor;this.approval=approval;this.definition=definition;
    }
    public String canonical(){return canonical;}
    public String sha256(){return sha256;}
    public Binding binding(){return binding;}
    public Actor actor(){return actor;}
    public Approval approval(){return approval;}
    public Definition definition(){return definition;}
    public static final String ORIGIN="ingestion",OPERATION="metadata.fixed-transfer.submit";
    public record Binding(String origin,String operation,long resourceId,long runId,long attemptId,long fence,
                          long executionRevision,String manifestSha256) { }
    public record Actor(String kind,long uid,String username,long accessVersion,String requestId) { }
    public record Approval(long uid,String username,long accessVersion,long revision,String definitionSha256) { }
    public record Definition(long sourceDatasourceId,long targetDatasourceId,String sourceSql,String targetTable,
                             String writeMode,int batchSize) { }
    public record Submit(long runId,long attemptId,long fence,long executionRevision,String manifestSha256) {
        public Submit {require(runId>0&&attemptId==0&&fence==1&&executionRevision>0&&manifestSha256!=null&&manifestSha256.matches("[a-f0-9]{64}"));}
        public static Submit parse(byte[] bytes) {
            JsonNode n=FixedJson.parse(bytes);fields(n,"runId","attemptId","fence","executionRevision","manifestSha256");
            return new Submit(number(n,"runId"),number(n,"attemptId"),number(n,"fence"),number(n,"executionRevision"),hash(n,"manifestSha256"));
        }
    }
    public static FixedTransferManifest parse(String canonical,String expectedHash,Submit expected) {
        require(canonical!=null&&canonical.getBytes(StandardCharsets.UTF_8).length<=768*1024);
        require(expectedHash!=null&&expectedHash.matches("[a-f0-9]{64}")&&BodyHash.sha256(canonical.getBytes(StandardCharsets.UTF_8)).equals(expectedHash));
        JsonNode n=FixedJson.parse(canonical);
        fields(n,"schemaVersion","origin","operation","resourceId","runId","attemptId","fence","executionRevision",
                "definitionSha256","actor","approval","execution","parameters","parent");
        require(number(n,"schemaVersion")==1&&ORIGIN.equals(text(n,"origin",16))&&OPERATION.equals(text(n,"operation",64)));
        long resource=number(n,"resourceId"),run=number(n,"runId"),attempt=number(n,"attemptId"),fence=number(n,"fence"),revision=number(n,"executionRevision");
        require(resource>0&&run==expected.runId()&&attempt==expected.attemptId()&&fence==expected.fence()&&revision==expected.executionRevision()
                &&expectedHash.equals(expected.manifestSha256()));
        require(n.get("parent").isNull());fields(n.get("parameters"));
        Actor actor=actor(n.get("actor"));Approval approval=approval(n.get("approval"));
        require(approval.revision()==revision&&approval.definitionSha256().equals(hash(n,"definitionSha256")));
        Definition definition=definition(n.get("execution"));
        require(approval.definitionSha256().equals(definitionHash(n.get("execution"))));
        return new FixedTransferManifest(canonical,expectedHash,new Binding(ORIGIN,OPERATION,resource,run,attempt,fence,revision,expectedHash),actor,approval,definition);
    }
    private static Actor actor(JsonNode n) {
        fields(n,"kind","uid","username","accessVersion","requestId");
        String kind=text(n,"kind",32),name=text(n,"username",64),request=text(n,"requestId",128);
        long uid=number(n,"uid"),av=number(n,"accessVersion");username(name);
        require(Set.of("manual","cron","pipeline").contains(kind)&&uid>0&&av>=0&&request.matches("[A-Za-z0-9._:-]{1,128}"));
        return new Actor(kind,uid,name,av,request);
    }
    private static Approval approval(JsonNode n) {
        fields(n,"uid","username","accessVersion","revision","definitionSha256");
        String name=text(n,"username",64);username(name);long uid=number(n,"uid"),av=number(n,"accessVersion"),revision=number(n,"revision");
        require(uid>0&&av>=0&&revision>0);return new Approval(uid,name,av,revision,hash(n,"definitionSha256"));
    }
    private static Definition definition(JsonNode n) {
        fields(n,"schemaVersion","sourceDatasourceId","targetDatasourceId","sourceSql","targetTable","writeMode","batchSize");
        require(number(n,"schemaVersion")==1);long source=number(n,"sourceDatasourceId"),target=number(n,"targetDatasourceId");
        require(source>0&&target>0);String sql=text(n,"sourceSql",200000),table=text(n,"targetTable",128);
        require(!sql.isBlank()&&table.trim().matches("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*){0,2}"));
        String mode=n.get("writeMode").isNull()?"append":text(n,"writeMode",32).trim().toLowerCase(Locale.ROOT);
        require(Set.of("append","replace").contains(mode));long batch=n.get("batchSize").isNull()?500:number(n,"batchSize");
        require(batch>=1&&batch<=5000);
        // Parse and reject structural/file/function escapes before queuing; JdbcService validates again.
        SqlSafetyPolicy.readOnly(sql);
        return new Definition(source,target,sql,table.trim(),mode,(int)batch);
    }
    /** PostgreSQL JSONB text for this fixed, flat, ASCII-key definition; actual PG vectors cover this encoding. */
    static String definitionHash(JsonNode n) {
        var keys=new ArrayList<String>();n.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        String text="{"+keys.stream().map(key->FixedJson.string(key)+": "+FixedJson.string(n.get(key))).collect(java.util.stream.Collectors.joining(", "))+"}";
        return BodyHash.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
    public Map<String,Object> authorizationRequest() {
        var request=new LinkedHashMap<String,Object>();request.put("schemaVersion",1);request.put("binding",binding);
        request.put("actor",actor);request.put("approval",approval);
        request.put("targets",Map.of("sourceDatasourceId",definition.sourceDatasourceId(),"targetDatasourceId",definition.targetDatasourceId()));
        request.put("parent",null);return Collections.unmodifiableMap(request);
    }
}
