package com.aetheris.auth.fixedexecution;

import com.aetheris.auth.validation.UsernameRules;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.Set;

/** Frozen v1 assertion supplied only by metadata after its signed source lookup. No general principal API. */
public record FixedExecutionRequest(int schemaVersion, Binding binding, Actor actor, Approval approval,
                                    Targets targets, Parent parent) {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxNumberLength(20)
                    .maxStringLength(65536).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public FixedExecutionRequest {
        require(schemaVersion==1 && binding!=null && actor!=null && approval!=null && targets!=null);
        require(approval.revision()==binding.executionRevision());
        switch(binding.origin()) {
            case "workflow" -> require(binding.operation().equals("metadata.fixed-sql.submit") && binding.attemptId()>0
                    && targets.targetDatasourceId()!=null && targets.sourceDatasourceId()==targets.targetDatasourceId());
            case "ingestion" -> require(binding.operation().equals("metadata.fixed-transfer.submit") && binding.attemptId()==0
                    && targets.targetDatasourceId()!=null);
            case "quality" -> require(binding.operation().equals("metadata.fixed-quality-query.submit") && binding.attemptId()==0
                    && targets.targetDatasourceId()==null);
            default -> throw invalid();
        }
        require(actor.kind().equals("workflow-child") ? binding.origin().equals("quality") && parent!=null : parent==null);
    }
    public record Binding(String origin,String operation,long resourceId,long runId,long attemptId,long fence,
                          long executionRevision,String manifestSha256) {
        public Binding {require(origin!=null && operation!=null && resourceId>0 && runId>0 && attemptId>=0 && fence>0 && executionRevision>0);hash(manifestSha256);}
    }
    public record Actor(String kind,long uid,String username,long accessVersion,String requestId) {
        public Actor {require(kind!=null && Set.of("manual","cron","pipeline","workflow-child").contains(kind));
            subject(uid,username,accessVersion);require(requestId!=null && requestId.matches("[A-Za-z0-9._:-]{1,128}"));}
    }
    public record Approval(long uid,String username,long accessVersion,long revision,String definitionSha256) {
        public Approval {subject(uid,username,accessVersion);require(revision>0);hash(definitionSha256);}
    }
    public record Targets(long sourceDatasourceId,Long targetDatasourceId) {
        public Targets {require(sourceDatasourceId>0 && (targetDatasourceId==null || targetDatasourceId>0));}
    }
    public record Parent(long resourceId,long runId,long attemptId,long fence,String manifestSha256,Approval approval) {
        public Parent {require(resourceId>0 && runId>0 && attemptId>0 && fence>0 && approval!=null);hash(manifestSha256);}
    }
    public static FixedExecutionRequest parse(byte[] raw) {
        require(raw!=null && raw.length>0 && raw.length<=64*1024);
        try {
            JsonNode n=JSON.readTree(raw);fields(n,"schemaVersion","binding","actor","approval","targets","parent");
            long version=number(n,"schemaVersion");require(version==1);
            return new FixedExecutionRequest(1,binding(n.get("binding")),actor(n.get("actor")),approval(n.get("approval")),
                    targets(n.get("targets")),n.get("parent").isNull()?null:parent(n.get("parent")));
        }catch(IOException failure){throw invalid();}
    }
    private static Binding binding(JsonNode n) {
        fields(n,"origin","operation","resourceId","runId","attemptId","fence","executionRevision","manifestSha256");
        return new Binding(text(n,"origin"),text(n,"operation"),number(n,"resourceId"),number(n,"runId"),number(n,"attemptId"),
                number(n,"fence"),number(n,"executionRevision"),text(n,"manifestSha256"));
    }
    private static Actor actor(JsonNode n) {
        fields(n,"kind","uid","username","accessVersion","requestId");
        return new Actor(text(n,"kind"),number(n,"uid"),text(n,"username"),number(n,"accessVersion"),text(n,"requestId"));
    }
    private static Approval approval(JsonNode n) {
        fields(n,"uid","username","accessVersion","revision","definitionSha256");
        return new Approval(number(n,"uid"),text(n,"username"),number(n,"accessVersion"),number(n,"revision"),text(n,"definitionSha256"));
    }
    private static Targets targets(JsonNode n) {
        fields(n,"sourceDatasourceId","targetDatasourceId");
        return new Targets(number(n,"sourceDatasourceId"),n.get("targetDatasourceId").isNull()?null:number(n,"targetDatasourceId"));
    }
    private static Parent parent(JsonNode n) {
        fields(n,"resourceId","runId","attemptId","fence","manifestSha256","approval");
        return new Parent(number(n,"resourceId"),number(n,"runId"),number(n,"attemptId"),number(n,"fence"),
                text(n,"manifestSha256"),approval(n.get("approval")));
    }
    private static void fields(JsonNode n,String... expected) {
        require(n!=null && n.isObject() && n.size()==expected.length);
        var names=Set.of(expected);n.fieldNames().forEachRemaining(name->require(names.contains(name)));
    }
    private static long number(JsonNode n,String name) {
        JsonNode value=n.get(name);require(value!=null && value.isIntegralNumber() && value.canConvertToLong());return value.longValue();
    }
    private static String text(JsonNode n,String name) {
        JsonNode value=n.get(name);require(value!=null && value.isTextual());return value.textValue();
    }
    private static void subject(long uid,String name,long av){require(uid>0 && av>=0 && UsernameRules.isValid(name));}
    private static void hash(String value){require(value!=null && value.matches("[0-9a-f]{64}"));}
    private static void require(boolean condition){if(!condition)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid fixed execution assertion");}
}
