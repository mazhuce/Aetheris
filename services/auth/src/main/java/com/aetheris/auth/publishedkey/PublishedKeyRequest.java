package com.aetheris.auth.publishedkey;

import com.aetheris.auth.validation.UsernameRules;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

/** Metadata's source-checked published asset assertion; no arbitrary action, subject lookup or caller ctx. */
public record PublishedKeyRequest(int schemaVersion,Binding binding,Owner owner,Target target) {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(4096).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public PublishedKeyRequest {require(schemaVersion==1&&binding!=null&&owner!=null&&target!=null);}
    public record Binding(String admissionId,long keyId,long assetId,String assetVersion,String admissionSha256,String parametersSha256,String requestId) {
        public Binding {
            require(admissionId!=null&&admissionId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")&&keyId>0&&assetId>0);
            require(assetVersion!=null&&assetVersion.length()<=32&&assetVersion.matches("v[1-9][0-9]*"));hash(admissionSha256);hash(parametersSha256);
            require(requestId!=null&&requestId.matches("[A-Za-z0-9._:-]{1,128}"));
        }
    }
    public record Owner(long uid,String username,long accessVersion) {
        public Owner {require(uid>0&&accessVersion>=0&&UsernameRules.isValid(username));}
    }
    public record Target(String resourceType,long resourceId,Long domainId,String domainCode) {
        public Target {
            require(resourceType!=null&&Set.of("table","metric").contains(resourceType)&&resourceId>0&&(domainId==null||domainId>0));
            require(domainCode==null||!domainCode.isBlank()&&domainCode.length()<=128&&domainCode.codePoints().noneMatch(Character::isISOControl));
        }
        public Map<String,String> context(){
            var ctx=new LinkedHashMap<String,String>();ctx.put(resourceType+"Id",Long.toString(resourceId));
            if(domainId!=null)ctx.put("domainId",domainId.toString());if(domainCode!=null)ctx.put("domainCode",domainCode);
            return Collections.unmodifiableMap(ctx);
        }
    }
    public static PublishedKeyRequest parse(byte[] raw){
        require(raw!=null&&raw.length>0&&raw.length<=16384);
        try{
            var n=JSON.readTree(raw);fields(n,"schemaVersion","binding","owner","target");require(number(n,"schemaVersion")==1);
            var b=n.get("binding");fields(b,"admissionId","keyId","assetId","assetVersion","admissionSha256","parametersSha256","requestId");
            var owner=n.get("owner");fields(owner,"uid","username","accessVersion");
            var target=n.get("target");fields(target,"resourceType","resourceId","domainId","domainCode");
            return new PublishedKeyRequest(1,new Binding(text(b,"admissionId"),number(b,"keyId"),number(b,"assetId"),text(b,"assetVersion"),text(b,"admissionSha256"),text(b,"parametersSha256"),text(b,"requestId")),
                    new Owner(number(owner,"uid"),text(owner,"username"),number(owner,"accessVersion")),
                    new Target(text(target,"resourceType"),number(target,"resourceId"),target.get("domainId").isNull()?null:number(target,"domainId"),target.get("domainCode").isNull()?null:text(target,"domainCode")));
        }catch(IOException invalid){throw invalid();}
    }
    private static void fields(JsonNode n,String... expected){require(n!=null&&n.isObject()&&n.size()==expected.length);var names=Set.of(expected);n.fieldNames().forEachRemaining(name->require(names.contains(name)));}
    private static long number(JsonNode n,String name){var v=n.get(name);require(v!=null&&v.isIntegralNumber()&&v.canConvertToLong());return v.longValue();}
    private static String text(JsonNode n,String name){var v=n.get(name);require(v!=null&&v.isTextual());return v.textValue();}
    private static void hash(String value){require(value!=null&&value.matches("[a-f0-9]{64}"));}
    private static void require(boolean condition){if(!condition)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid published key assertion");}
}
