package com.aetheris.security.protocol.execution;

import com.aetheris.security.protocol.BodyHash;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Fixed quality-parent wire contract. Only a signed lookup supplies canonical database bytes. */
public final class WorkflowQualityParent {
    public static final String OPERATION="quality.workflow-child.submit";
    public static final String LOOKUP_OPERATION="workflow.quality-parent.lookup";
    public static final String LOOKUP_PATH="/internal/v2/quality-parent/lookup";
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(1048576).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Reference(long instanceId,long attemptId,long fence,long executionRevision,String manifestSha256) {
        public Reference {require(instanceId>0&&attemptId>0&&fence>0&&executionRevision>0);digest(manifestSha256);}
        public static Reference parse(byte[] raw){var n=read(raw,65536);fields(n,"instanceId","attemptId","fence","executionRevision","manifestSha256");return new Reference(number(n,"instanceId",1),number(n,"attemptId",1),number(n,"fence",1),number(n,"executionRevision",1),hash(n,"manifestSha256"));}
    }
    private final Reference reference;
    private final long resourceId,qualityRuleId,qualityExecutionRevision;
    private final String qualityDefinitionSha256,bizDate;
    private final ObjectNode actor,approval;
    private WorkflowQualityParent(Reference ref,long task,long rule,long ruleRevision,String ruleHash,String date,ObjectNode actor,ObjectNode approval){
        reference=ref;resourceId=task;qualityRuleId=rule;qualityExecutionRevision=ruleRevision;qualityDefinitionSha256=ruleHash;bizDate=date;this.actor=actor.deepCopy();this.approval=approval.deepCopy();
    }
    public Reference reference(){return reference;}
    public long resourceId(){return resourceId;}
    public long qualityRuleId(){return qualityRuleId;}
    public long qualityExecutionRevision(){return qualityExecutionRevision;}
    public String qualityDefinitionSha256(){return qualityDefinitionSha256;}
    public String bizDate(){return bizDate;}
    public ObjectNode actor(){return actor.deepCopy();}
    public ObjectNode childActor(){return actor.deepCopy().put("kind","workflow-child");}
    public ObjectNode approval(){return approval.deepCopy();}
    public ObjectNode parent(){var n=JSON.createObjectNode().put("resourceId",resourceId).put("runId",reference.instanceId()).put("attemptId",reference.attemptId()).put("fence",reference.fence()).put("manifestSha256",reference.manifestSha256());n.set("approval",approval());return n;}
    public static WorkflowQualityParent parse(String canonical,String expectedHash,Reference ref){
        Objects.requireNonNull(ref);require(canonical!=null&&canonical.getBytes(StandardCharsets.UTF_8).length<=768*1024
                &&BodyHash.sha256(canonical.getBytes(StandardCharsets.UTF_8)).equals(expectedHash)&&ref.manifestSha256().equals(expectedHash));
        var n=read(canonical.getBytes(StandardCharsets.UTF_8),768*1024);
        fields(n,"schemaVersion","origin","operation","resourceId","runId","attemptId","fence","executionRevision","definitionSha256","actor","approval","execution","parameters","parent");
        require(number(n,"schemaVersion",1)==1&&"workflow".equals(text(n,"origin"))&&OPERATION.equals(text(n,"operation"))&&n.get("parent").isNull());
        require(number(n,"runId",1)==ref.instanceId()&&number(n,"attemptId",1)==ref.attemptId()&&number(n,"fence",1)==ref.fence()&&number(n,"executionRevision",1)==ref.executionRevision());
        var a=n.get("actor");fields(a,"kind","uid","username","accessVersion","requestId");identity(a);
        require(Set.of("manual","cron","pipeline").contains(text(a,"kind"))&&text(a,"requestId").matches("[A-Za-z0-9._:-]{1,128}"));
        var p=n.get("approval");fields(p,"uid","username","accessVersion","revision","definitionSha256");identity(p);
        require(number(p,"revision",1)==ref.executionRevision()&&hash(n,"definitionSha256").equals(hash(p,"definitionSha256")));
        var d=n.get("execution");validateDefinition(d);require(hash(n,"definitionSha256").equals(definitionHash(d)));
        fields(n.get("parameters"),"bizDate");String date=text(n.get("parameters"),"bizDate");
        require(date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));try{var parsed=LocalDate.parse(date);require(parsed.getYear()>=1&&parsed.toString().equals(date));}catch(java.time.DateTimeException bad){throw invalid();}
        return new WorkflowQualityParent(ref,number(n,"resourceId",1),number(d,"qualityRuleId",1),number(d,"qualityExecutionRevision",1),hash(d,"qualityDefinitionSha256"),date,(ObjectNode)a,(ObjectNode)p);
    }
    public static void validateDefinition(JsonNode d){
        fields(d,"schemaVersion","taskType","datasourceId","sql","timeoutSeconds","maxRetries","targetTable","jobDefinitionId","qualityRuleId","flinkEndpoint","flinkMode","eventTimeColumn","watermarkExpression","allowedLatenessSeconds","lateDataPolicy","deadLetterTable","dependencies","qualityExecutionRevision","qualityDefinitionSha256");
        require(number(d,"schemaVersion",1)==1&&"quality".equals(text(d,"taskType")));number(d,"qualityRuleId",1);number(d,"qualityExecutionRevision",1);hash(d,"qualityDefinitionSha256");
        for(String key:List.of("datasourceId","jobDefinitionId","flinkEndpoint","eventTimeColumn","watermarkExpression","deadLetterTable"))require(d.get(key).isNull());
        // V21 gives every task the non-streaming zero default; it grants no late-data capability.
        require(d.get("allowedLatenessSeconds").isNull()||number(d,"allowedLatenessSeconds",0)==0);
        require(d.get("sql").isNull()||d.get("sql").isTextual()&&d.get("sql").textValue().isBlank());
        if(!d.get("timeoutSeconds").isNull())require(number(d,"timeoutSeconds",1)<=3600);
        if(!d.get("maxRetries").isNull())require(number(d,"maxRetries",0)<=100);
        var deps=d.get("dependencies");require(deps.isObject());if(!deps.isEmpty()){fields(deps,"upstreamTaskIds");var ids=deps.get("upstreamTaskIds");require(ids.isArray());long last=0;for(var id:ids){require(id.isIntegralNumber()&&id.canConvertToLong()&&id.longValue()>last);last=id.longValue();}}
    }
    public static String definitionHash(JsonNode node){return BodyHash.sha256(jsonb(node).getBytes(StandardCharsets.UTF_8));}
    private static String jsonb(JsonNode n){
        require(n!=null);if(n.isNull())return "null";if(n.isTextual())return quote(n.textValue());
        if(n.isIntegralNumber()){require(n.canConvertToLong());return Long.toString(n.longValue());}
        if(n.isArray()){var items=new ArrayList<String>();n.forEach(v->items.add(jsonb(v)));return "["+String.join(", ",items)+"]";}
        require(n.isObject());var keys=new ArrayList<String>();n.fieldNames().forEachRemaining(k->{require(k.matches("[A-Za-z][A-Za-z0-9]*"));keys.add(k);});keys.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        return "{"+keys.stream().map(k->quote(k)+": "+jsonb(n.get(k))).collect(java.util.stream.Collectors.joining(", "))+"}";
    }
    private static String quote(String s){
        var out=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);require(c!=0);if(Character.isHighSurrogate(c)){require(i+1<s.length()&&Character.isLowSurrogate(s.charAt(i+1)));out.append(c).append(s.charAt(++i));continue;}require(!Character.isLowSurrogate(c));switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}}return out.append('"').toString();
    }
    private static JsonNode read(byte[] bytes,int limit){require(bytes!=null&&bytes.length>0&&bytes.length<=limit);try{return JSON.readTree(bytes);}catch(Exception bad){throw invalid();}}
    private static void fields(JsonNode n,String...keys){require(n!=null&&n.isObject()&&n.size()==keys.length);var expected=Set.of(keys);n.fieldNames().forEachRemaining(k->require(expected.contains(k)));}
    private static long number(JsonNode n,String k,long min){var v=n.get(k);require(v!=null&&v.isIntegralNumber()&&v.canConvertToLong()&&v.longValue()>=min);return v.longValue();}
    private static String text(JsonNode n,String k){var v=n.get(k);require(v!=null&&v.isTextual());return v.textValue();}
    private static String hash(JsonNode n,String k){String s=text(n,k);digest(s);return s;}
    private static void digest(String s){require(s!=null&&s.matches("[a-f0-9]{64}"));}
    private static void identity(JsonNode n){number(n,"uid",1);number(n,"accessVersion",0);String s=text(n,"username");require(!s.isEmpty()&&s.length()<=64);s.codePoints().forEach(c->require(c!='|'&&!Character.isISOControl(c)&&!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&Character.getType(c)!=Character.FORMAT&&(c<0xD800||c>0xDFFF)));}
    private static void require(boolean condition){if(!condition)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid sealed quality parent");}
}
