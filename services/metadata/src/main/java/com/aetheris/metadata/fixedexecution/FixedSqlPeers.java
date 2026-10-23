package com.aetheris.metadata.fixedexecution;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;

/** The live source lease is trusted timing context, never serialized into a manifest or stored plan. */
public final class FixedSqlPeers {
    public record Resolved(FixedSqlManifest manifest,FixedExecutionAdapter.SourceLease lease){}
    private final SignedHttpClient source,auth;private final Clock clock;
    public FixedSqlPeers(SignedHttpClient source,SignedHttpClient auth,Clock clock){this.source=Objects.requireNonNull(source);this.auth=Objects.requireNonNull(auth);this.clock=Objects.requireNonNull(clock);}
    public Resolved resolveAndAuthorize(FixedExecutionAdapter.Submission input){
        FixedExecutionOperation.SQL.validate(input);
        try{
            var envelope=post(source,"workflow","/internal/v2/executions/lookup","workflow.execution.lookup",Map.of("instanceId",input.runId(),"attemptId",input.attemptId(),"fence",input.fence()),true);
            fields(envelope,"canonicalManifest","manifestSha256","status","leaseExpiresAt");long now=clock.instant().getEpochSecond(),lease=number(envelope,"leaseExpiresAt");
            var manifest=FixedSqlManifest.parse(text(envelope,"canonicalManifest",768*1024),hash(envelope,"manifestSha256"),input);
            if(!"running".equals(text(envelope,"status",16))||lease<now+5||lease>now+manifest.definition().timeoutSeconds()+60L)throw new FixedTransferPeers.Rejected("SOURCE_NOT_ACTIVE");
            var result=post(auth,"auth","/internal/v2/permissions/fixed-execution-authorize","auth.fixed-execution.authorize",manifest.authorizationRequest(),false);
            fields(result,"binding","allowed","reason","checks");require(parse(bytes(manifest.binding())).equals(result.get("binding")));boolean allowed=bool(result,"allowed");var reason=result.get("reason");var checks=result.get("checks");require((reason.isNull()||reason.isTextual())&&checks.isArray());
            if(!allowed&&"SUBJECT_INACTIVE".equals(reason.asText())&&checks.isEmpty())throw new FixedTransferPeers.Rejected("EXECUTION_NOT_AUTHORIZED");
            require(checks.size()==3);boolean all=check(checks.get(0),"actor","workflow_task",manifest.binding().resourceId(),"execute")&check(checks.get(1),"approval","datasource",manifest.definition().datasourceId(),"query")&check(checks.get(2),"approval","datasource",manifest.definition().datasourceId(),"write");
            if(allowed){require(all&&reason.isNull());return new Resolved(manifest,new FixedExecutionAdapter.SourceLease(Instant.ofEpochSecond(lease)));}
            require(!all&&"PERMISSION_DENIED".equals(reason.asText()));throw new FixedTransferPeers.Rejected("EXECUTION_NOT_AUTHORIZED");
        }catch(FixedTransferPeers.Rejected|FixedTransferPeers.Unavailable failure){throw failure;}
        catch(RuntimeException invalid){throw new FixedTransferPeers.Unavailable();}
    }
    private static boolean check(JsonNode n,String subject,String resource,long id,String action){fields(n,"subject","resourceType","resourceId","action","allowed");require(subject.equals(text(n,"subject",32))&&resource.equals(text(n,"resourceType",32))&&id==number(n,"resourceId")&&action.equals(text(n,"action",32)));return bool(n,"allowed");}
    private static JsonNode post(SignedHttpClient client,String receiver,String path,String operation,Object body,boolean lookup){
        var pending=client.sendAsync(new SignedHttpClient.Request(receiver,"POST",path,operation,bytes(body)));
        try{var response=pending.get(2500,TimeUnit.MILLISECONDS);if(lookup&&Set.of(403,404,409).contains(response.status()))throw new FixedTransferPeers.Rejected("SOURCE_NOT_ACTIVE");if(response.status()!=200)throw new FixedTransferPeers.Unavailable();return parse(response.body());}
        catch(FixedTransferPeers.Rejected|FixedTransferPeers.Unavailable failure){throw failure;}
        catch(Exception failure){pending.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new FixedTransferPeers.Unavailable();}
    }
}
