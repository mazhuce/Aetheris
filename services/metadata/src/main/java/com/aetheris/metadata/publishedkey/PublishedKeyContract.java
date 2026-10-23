package com.aetheris.metadata.publishedkey;

import com.aetheris.metadata.entity.ApiAsset;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Strict, source-bound published-query messages. None of these records is a USER principal. */
public final class PublishedKeyContract {
    private PublishedKeyContract(){}
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(32768).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Reference(String admissionId,String admissionSha256,String parametersSha256){
        public Reference{uuid(admissionId);hash(admissionSha256);hash(parametersSha256);}
    }
    public record Owner(long uid,String username,long accessVersion){
        public Owner{require(uid>0&&accessVersion>=0&&username!=null&&!username.isEmpty()&&username.length()<=64
                &&username.codePoints().noneMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c=='|'||c>=0xd800&&c<=0xdfff));}
    }
    public record Asset(long id,String assetCode,String version,String targetType,String targetRef){
        public Asset{require(id>0&&code(assetCode)&&version!=null&&version.length()<=32&&version.matches("v[1-9][0-9]*")
                &&targetType!=null&&targetRef!=null&&Set.of("table","metric").contains(targetType));
            if(targetType.equals("metric"))require(code(targetRef));
            else{try{require(targetRef.length()<=128&&Long.parseLong(targetRef)>0);}catch(NumberFormatException bad){throw invalid();}}}
        public static Asset from(ApiAsset asset){require(asset!=null&&asset.getId()!=null&&"published".equals(asset.getStatus())&&"POST".equals(asset.getHttpMethod()));return new Asset(asset.getId(),asset.getAssetCode(),asset.getVersion(),asset.getTargetType(),asset.getTargetRef());}
    }
    public record Parameters(List<String> dimensions,String timeGrain,String startDate,String endDate,List<String> columns,int limit){
        public Parameters{
            dimensions=identifiers(dimensions,8,"[A-Za-z][A-Za-z0-9_]{1,63}");columns=identifiers(columns,50,"[A-Za-z_][A-Za-z0-9_$]{0,127}");
            require(timeGrain==null||Set.of("none","day","month","year").contains(timeGrain));date(startDate);date(endDate);
            require(startDate==null||endDate==null||startDate.compareTo(endDate)<=0);require(limit>=1&&limit<=100);
        }
        public String sha256(){return sha(bytes(this));}
    }
    public record Admission(String admissionId,long keyId,Owner owner,Asset asset,Parameters parameters,String parametersSha256,String requestId,long createdAtEpochMillis,long expiresAtEpochMillis){
        public Admission{uuid(admissionId);require(keyId>0&&owner!=null&&asset!=null&&parameters!=null);hash(parametersSha256);
            require(parameters.sha256().equals(parametersSha256)&&requestId!=null&&requestId.matches("[A-Za-z0-9._:-]{1,128}"));
            require(createdAtEpochMillis>0&&createdAtEpochMillis<=Long.MAX_VALUE-30000&&expiresAtEpochMillis==createdAtEpochMillis+30000);}
        public void requireLive(Clock clock){long now=clock.millis();if(createdAtEpochMillis>now+1000||expiresAtEpochMillis<=now)throw new Rejected();}
        public Map<String,Object> binding(Reference ref){return Map.of("admissionId",admissionId,"keyId",keyId,"assetId",asset.id(),"assetVersion",asset.version(),"admissionSha256",ref.admissionSha256(),"parametersSha256",parametersSha256,"requestId",requestId);}
    }
    public record Target(String resourceType,long resourceId,Long domainId,String domainCode){
        public Target{require(Set.of("table","metric").contains(resourceType)&&resourceId>0&&(domainId==null||domainId>0)
                &&(domainCode==null||!domainCode.isBlank()&&domainCode.length()<=128&&domainCode.codePoints().noneMatch(Character::isISOControl)));}
    }
    public static Reference reference(byte[] raw){var n=parse(raw,4096);fields(n,"admissionId","admissionSha256","parametersSha256");return new Reference(text(n,"admissionId"),text(n,"admissionSha256"),text(n,"parametersSha256"));}
    public static String resolveCode(byte[] raw){var n=parse(raw,4096);fields(n,"assetCode");var code=text(n,"assetCode");require(code(code));return code;}
    public static Admission admission(String raw,Reference ref,Clock clock){
        require(raw!=null);byte[] encoded=raw.getBytes(StandardCharsets.UTF_8);require(sha(encoded).equals(ref.admissionSha256()));var n=parse(encoded,32768);
        fields(n,"schemaVersion","admissionId","keyId","owner","asset","parameters","parametersSha256","requestId","createdAtEpochMillis","expiresAtEpochMillis");require(number(n,"schemaVersion")==1);
        var o=n.get("owner");fields(o,"uid","username","accessVersion");
        var a=n.get("asset");fields(a,"id","assetCode","version","targetType","targetRef");
        var p=n.get("parameters");fields(p,"dimensions","timeGrain","startDate","endDate","columns","limit");long limit=number(p,"limit");require(limit>=1&&limit<=100);
        var value=new Admission(text(n,"admissionId"),number(n,"keyId"),new Owner(number(o,"uid"),text(o,"username"),number(o,"accessVersion")),
                new Asset(number(a,"id"),text(a,"assetCode"),text(a,"version"),text(a,"targetType"),text(a,"targetRef")),
                new Parameters(strings(p,"dimensions"),nullable(p,"timeGrain"),nullable(p,"startDate"),nullable(p,"endDate"),strings(p,"columns"),(int)limit),
                text(n,"parametersSha256"),text(n,"requestId"),number(n,"createdAtEpochMillis"),number(n,"expiresAtEpochMillis"));
        require(ref.admissionId().equals(value.admissionId())&&ref.parametersSha256().equals(value.parametersSha256()));value.requireLive(clock);return value;
    }
    static JsonNode parse(byte[] raw,int max){require(raw!=null&&raw.length>0&&raw.length<=max);try{return JSON.readTree(raw);}catch(Exception malformed){throw invalid();}}
    static void fields(JsonNode n,String...names){require(n!=null&&n.isObject()&&n.size()==names.length);var allowed=Set.of(names);n.fieldNames().forEachRemaining(name->require(allowed.contains(name)));}
    static String text(JsonNode n,String key){var v=n.get(key);require(v!=null&&v.isTextual());return v.textValue();}
    static long number(JsonNode n,String key){var v=n.get(key);require(v!=null&&v.isIntegralNumber()&&v.canConvertToLong());return v.longValue();}
    static boolean bool(JsonNode n,String key){var v=n.get(key);require(v!=null&&v.isBoolean());return v.booleanValue();}
    static byte[] bytes(Object value){try{return JSON.writeValueAsBytes(value);}catch(Exception error){throw invalid();}}
    static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception error){throw new IllegalStateException(error);}}
    static void require(boolean condition){if(!condition)throw invalid();}
    private static String nullable(JsonNode n,String key){require(n.has(key));return n.get(key).isNull()?null:text(n,key);}
    private static List<String> strings(JsonNode n,String key){var values=n.get(key);require(values!=null&&values.isArray());var result=new ArrayList<String>();for(var v:values){require(v.isTextual());result.add(v.textValue());}return result;}
    private static List<String> identifiers(List<String> values,int max,String pattern){require(values!=null&&values.size()<=max);var seen=new HashSet<String>();for(var value:values)require(value!=null&&value.matches(pattern)&&seen.add(value));return List.copyOf(values);}
    private static void date(String value){if(value==null)return;require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));try{LocalDate.parse(value);}catch(Exception bad){throw invalid();}}
    private static boolean code(String value){return value!=null&&value.matches("[A-Za-z][A-Za-z0-9_]{1,63}");}
    private static void hash(String value){require(value!=null&&value.matches("[a-f0-9]{64}"));}
    private static void uuid(String value){require(value!=null&&value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"));}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid published key message");}
    public static final class Rejected extends RuntimeException{public Rejected(){super("Published query admission rejected");}}
    public static final class Unavailable extends RuntimeException{public Unavailable(){super("Published query dependency unavailable");}}
}
