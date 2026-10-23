package com.aetheris.metadata.fixedexecution;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

/** Fixed source readback followed by a bound auth decision. No URL or principal comes from submit. */
public final class FixedTransferPeers {
    private final SignedHttpClient source,auth;
    private final Clock clock;
    public FixedTransferPeers(SignedHttpClient source,SignedHttpClient auth,Clock clock) {
        this.source=Objects.requireNonNull(source);this.auth=Objects.requireNonNull(auth);this.clock=Objects.requireNonNull(clock);
    }
    public FixedTransferManifest resolveAndAuthorize(FixedTransferManifest.Submit input) {
        try {
            JsonNode envelope=post(source,"ingestion","/internal/v2/executions/lookup","ingestion.execution.lookup",
                    Map.of("runId",input.runId(),"fence",input.fence()),true);
            fields(envelope,"canonicalManifest","manifestSha256","status","leaseExpiresAt");
            long now=clock.instant().getEpochSecond(),lease=number(envelope,"leaseExpiresAt");
            if(!"running".equals(text(envelope,"status",16))||lease<now+5||lease>now+180)throw new Rejected("SOURCE_NOT_ACTIVE");
            var manifest=FixedTransferManifest.parse(text(envelope,"canonicalManifest",768*1024),hash(envelope,"manifestSha256"),input);
            JsonNode decision=post(auth,"auth","/internal/v2/permissions/fixed-execution-authorize","auth.fixed-execution.authorize",manifest.authorizationRequest(),false);
            verifyDecision(manifest,decision);return manifest;
        }catch(Rejected|Unavailable failure){throw failure;}
        catch(RuntimeException failure){throw new Unavailable();}
    }
    private static JsonNode post(SignedHttpClient client,String receiver,String path,String operation,Object body,boolean sourceReadback) {
        var pending=client.sendAsync(new SignedHttpClient.Request(receiver,"POST",path,operation,bytes(body)));
        try {
            var response=pending.get(2500,TimeUnit.MILLISECONDS);
            if(sourceReadback&&Set.of(403,404,409).contains(response.status()))throw new Rejected("SOURCE_NOT_ACTIVE");
            if(response.status()!=200)throw new Unavailable();
            return FixedJson.parse(response.body());
        }catch(Rejected|Unavailable failure){throw failure;}
        catch(Exception failure){pending.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new Unavailable();}
    }
    private static void verifyDecision(FixedTransferManifest manifest,JsonNode response) {
        fields(response,"binding","allowed","reason","checks");
        // Parse both byte representations so integral JSON node widths do not depend on Java record field widths.
        require(FixedJson.parse(bytes(manifest.binding())).equals(response.get("binding")));
        boolean allowed=bool(response,"allowed");JsonNode reason=response.get("reason"),checks=response.get("checks");
        require(reason.isNull()||reason.isTextual());require(checks.isArray());
        if(!allowed&&"SUBJECT_INACTIVE".equals(reason.asText())&&checks.isEmpty())throw new Rejected("EXECUTION_NOT_AUTHORIZED");
        var expected=List.of(new Check("actor","ingestion_task",manifest.binding().resourceId(),"execute"),
                new Check("approval","datasource",manifest.definition().sourceDatasourceId(),"query"),
                new Check("approval","datasource",manifest.definition().targetDatasourceId(),"write"));
        require(checks.size()==expected.size());boolean all=true;
        for(int index=0;index<expected.size();index++) {
            JsonNode item=checks.get(index);Check wanted=expected.get(index);
            fields(item,"subject","resourceType","resourceId","action","allowed");
            require(wanted.subject().equals(text(item,"subject",32))&&wanted.resourceType().equals(text(item,"resourceType",32))
                    &&wanted.resourceId()==number(item,"resourceId")&&wanted.action().equals(text(item,"action",32)));
            all&=bool(item,"allowed");
        }
        if(allowed){require(all&&reason.isNull());return;}
        require(!all&&"PERMISSION_DENIED".equals(reason.asText()));throw new Rejected("EXECUTION_NOT_AUTHORIZED");
    }
    private record Check(String subject,String resourceType,long resourceId,String action) { }
    public static final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code){super("Fixed execution admission rejected");this.code=code;}
        public String code(){return code;}
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable(){super("Fixed execution source or authorization unavailable");}
    }
}
