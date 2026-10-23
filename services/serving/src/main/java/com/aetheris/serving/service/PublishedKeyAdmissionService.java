package com.aetheris.serving.service;

import com.aetheris.security.protocol.BodyHash;
import com.aetheris.serving.dto.ApiAssetQueryRequest;
import com.aetheris.serving.security.ServingSecurity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.aetheris.serving.service.PublishedKeyJson.*;

/** Single-use read admission. No HTTP, user proof or key secret crosses its short transactions. */
@Service
public class PublishedKeyAdmissionService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PublishedKeyAdmissionService.class);
    private final JdbcTemplate jdbc;private final ApiKeyService keys;
    public PublishedKeyAdmissionService(JdbcTemplate jdbc,ApiKeyService keys){this.jdbc=jdbc;this.keys=keys;}
    public record Reference(UUID admissionId,String admissionSha256,String parametersSha256) {
        public Map<String,Object> map(){return Map.of("admissionId",admissionId.toString(),"admissionSha256",admissionSha256,"parametersSha256",parametersSha256);}
        public static Reference parse(byte[] bytes){if(bytes==null||bytes.length>4096)throw bad();var n=PublishedKeyJson.parse(bytes);fields(n,Set.of("admissionId","admissionSha256","parametersSha256"));return new Reference(uuid(n,"admissionId"),hash(n,"admissionSha256"),hash(n,"parametersSha256"));}
    }
    public record Admission(Reference reference,long keyId,ApiKeyOwner owner,String requestId) {}
    public record Redeemed(String canonicalAdmission,String admissionSha256) {}
    public Admission create(String secret,Map<String,Object> asset,ApiAssetQueryRequest query,String requestId) {
        var published=asset(asset);var parameters=PublishedKeyParameters.from(query);
        String safeRequestId=requestId!=null&&requestId.matches("[A-Za-z0-9._:-]{1,128}")?requestId:UUID.randomUUID().toString();
        return keys.transaction().execute(status->{
            limits();var key=keys.authenticate(secret,true).orElseThrow(()->new ServingSecurity.UnauthorizedException("API key 无效或已过期"));
            var owner=key.owner();
            if(!keys.consumeQuota(key.id()))throw new QuotaExceeded();
            long created=now(),expires=Math.addExact(created,30000);UUID id=UUID.randomUUID();
            var sealed=new LinkedHashMap<String,Object>();sealed.put("schemaVersion",1);sealed.put("admissionId",id.toString());sealed.put("keyId",key.id());
            var ownerFields=new LinkedHashMap<String,Object>();ownerFields.put("uid",owner.uid());ownerFields.put("username",owner.username());ownerFields.put("accessVersion",owner.accessVersion());
            sealed.put("owner",ownerFields);sealed.put("asset",published);
            sealed.put("parameters",parameters.map());sealed.put("parametersSha256",parameters.sha256());sealed.put("requestId",safeRequestId);sealed.put("createdAtEpochMillis",created);sealed.put("expiresAtEpochMillis",expires);
            String canonical=string(sealed);String digest=BodyHash.sha256(canonical.getBytes(StandardCharsets.UTF_8));
            if(canonical.getBytes(StandardCharsets.UTF_8).length>16384)throw bad();
            jdbc.update("""
                INSERT INTO serving.published_key_admission(id,key_id,owner_uid,owner_username,owner_access_version,asset_id,asset_version,
                    canonical_admission,admission_sha256,parameters_sha256,created_at_epoch_millis,expires_at_epoch_millis)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,key.id(),owner.uid(),owner.username(),owner.accessVersion(),published.get("id"),published.get("version"),canonical,digest,parameters.sha256(),created,expires);
            return new Admission(new Reference(id,digest,parameters.sha256()),key.id(),owner,safeRequestId);
        });
    }
    public Redeemed redeem(byte[] bytes) {
        Reference expected=Reference.parse(bytes);
        return keys.transaction().execute(status->{
            limits();var ids=jdbc.query("SELECT key_id FROM serving.published_key_admission WHERE id=?",(r,i)->r.getLong(1),expected.admissionId());
            if(ids.isEmpty())throw new NotFound();
            // Shared lock order with rotate/revoke: key first, admission second.
            var owners=jdbc.query("SELECT owner_uid,owner_username,owner_access_version FROM serving.service_api_key WHERE id=? AND status='active' AND owner_uid IS NOT NULL AND owner_access_version IS NOT NULL AND (expires_at IS NULL OR expires_at>clock_timestamp()) FOR UPDATE",
                    (r,i)->ApiKeyService.bound(r,"owner_uid","owner_username","owner_access_version"),ids.getFirst());
            if(owners.isEmpty())throw new Conflict();
            var rows=jdbc.query("SELECT canonical_admission,admission_sha256,parameters_sha256,created_at_epoch_millis,expires_at_epoch_millis,redeemed_at_epoch_millis,owner_uid,owner_username,owner_access_version FROM serving.published_key_admission WHERE id=? FOR UPDATE",
                    (r,i)->new Stored(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getLong(5),r.getObject(6,Long.class),ApiKeyService.bound(r,"owner_uid","owner_username","owner_access_version")),expected.admissionId());
            if(rows.isEmpty())throw new NotFound();var row=rows.getFirst();
            if(row.redeemed()!=null||!owners.getFirst().equals(row.owner())||!expected.admissionSha256().equals(row.hash())||!expected.parametersSha256().equals(row.parametersHash()))throw new Conflict();
            long now=now();if(now<row.created()||now>=row.expires()||row.expires()-row.created()!=30000)throw new Conflict();
            validate(row,expected,ids.getFirst());
            if(jdbc.update("UPDATE serving.published_key_admission SET redeemed_at_epoch_millis=? WHERE id=? AND redeemed_at_epoch_millis IS NULL AND expires_at_epoch_millis>?",now,expected.admissionId(),now)!=1)throw new Conflict();
            return new Redeemed(row.canonical(),row.hash());
        });
    }
    private void validate(Stored row,Reference expected,long keyId){
        if(!row.hash().equals(BodyHash.sha256(row.canonical().getBytes(StandardCharsets.UTF_8))))throw new Conflict();
        JsonNode n=parse(row.canonical().getBytes(StandardCharsets.UTF_8));
        fields(n,Set.of("schemaVersion","admissionId","keyId","owner","asset","parameters","parametersSha256","requestId","createdAtEpochMillis","expiresAtEpochMillis"));
        if(integer(n,"schemaVersion",1)!=1||!uuid(n,"admissionId").equals(expected.admissionId())||integer(n,"keyId",1)!=keyId||integer(n,"createdAtEpochMillis",1)!=row.created()||integer(n,"expiresAtEpochMillis",1)!=row.expires()||!hash(n,"parametersSha256").equals(row.parametersHash()))throw new Conflict();
        var owner=n.get("owner");fields(owner,Set.of("uid","username","accessVersion"));
        if(!new ApiKeyOwner(integer(owner,"uid",1),text(owner,"username",64),integer(owner,"accessVersion",0)).equals(row.owner()))throw new Conflict();
        fields(n.get("asset"),Set.of("id","assetCode","version","targetType","targetRef"));
        @SuppressWarnings("unchecked") Map<String,Object> a=JSON.convertValue(n.get("asset"),Map.class);asset(a);
        fields(n.get("parameters"),Set.of("dimensions","timeGrain","startDate","endDate","columns","limit"));
        var parameters=PublishedKeyParameters.from(PublishedKeyParameters.publicRequest(bytes(n.get("parameters"))));
        if(!parameters.sha256().equals(row.parametersHash())||!text(n,"requestId",128).matches("[A-Za-z0-9._:-]{1,128}"))throw new Conflict();
    }
    public static Map<String,Object> asset(Map<String,Object> input){
        try{return checkedAsset(input);}catch(IllegalArgumentException invalid){throw invalidAsset();}
    }
    private static Map<String,Object> checkedAsset(Map<String,Object> input){
        if(input==null||!input.keySet().equals(Set.of("id","assetCode","version","targetType","targetRef")))throw invalidAsset();
        var n=JSON.valueToTree(input);long id=integer(n,"id",1);String code=text(n,"assetCode",64),version=text(n,"version",32),type=text(n,"targetType",16),ref=text(n,"targetRef",128);
        if(!code.matches("[A-Za-z][A-Za-z0-9_]{1,63}")||!version.matches("v[1-9][0-9]*")||!Set.of("table","metric").contains(type))throw invalidAsset();
        if(type.equals("metric")&&!ref.matches("[A-Za-z][A-Za-z0-9_]{1,63}"))throw invalidAsset();
        if(type.equals("table")){try{if(Long.parseLong(ref)<=0)throw invalidAsset();}catch(NumberFormatException invalid){throw invalidAsset();}}
        var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("assetCode",code);result.put("version",version);result.put("targetType",type);result.put("targetRef",ref);return Collections.unmodifiableMap(result);
    }
    private static ResponseStatusException invalidAsset(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"发布资产契约不可用");}
    private long now(){return jdbc.queryForObject("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint",Long.class);}
    private void limits(){jdbc.execute("SET LOCAL lock_timeout='2s'");jdbc.execute("SET LOCAL statement_timeout='2s'");}
    /** Per replica: at most one fixed-size batch per hour. UUIDs are never reused or re-created by callers. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000,initialDelay=3600000)
    public void cleanup(){try{purgeExpired();}catch(RuntimeException unavailable){LOG.warn("Published-key admission cleanup unavailable");}}
    public int purgeExpired(){return keys.transaction().execute(status->{limits();return jdbc.update("""
        WITH expired AS (
            SELECT id FROM serving.published_key_admission
             WHERE expires_at_epoch_millis < floor(extract(epoch FROM clock_timestamp())*1000)::bigint-86400000
             ORDER BY expires_at_epoch_millis,id LIMIT 1000
        ) DELETE FROM serving.published_key_admission a USING expired e WHERE a.id=e.id
        """);});}
    private record Stored(String canonical,String hash,String parametersHash,long created,long expires,Long redeemed,ApiKeyOwner owner){}
    public static class Conflict extends RuntimeException{public Conflict(){super("API key 准入已失效或已领取");}}
    public static class NotFound extends RuntimeException{public NotFound(){super("API key 准入不存在");}}
    public static class QuotaExceeded extends RuntimeException{}
}
