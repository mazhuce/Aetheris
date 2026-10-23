package com.aetheris.metadata.publishedkey;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.aetheris.metadata.publishedkey.PublishedKeyContract.*;

/** Two fixed workload operations. A missing response never reissues either POST. */
public final class PublishedKeyPeers {
    private final SignedHttpClient serving,auth;private final Clock clock;
    public PublishedKeyPeers(SignedHttpClient serving,SignedHttpClient auth,Clock clock){this.serving=Objects.requireNonNull(serving);this.auth=Objects.requireNonNull(auth);this.clock=Objects.requireNonNull(clock);}
    public Admission redeem(Reference ref){
        try{var envelope=post(serving,"serving","/internal/v2/published-key-admissions/redeem","serving.published-key.redeem",ref,true);
            fields(envelope,"canonicalAdmission","admissionSha256");require(ref.admissionSha256().equals(text(envelope,"admissionSha256")));
            return admission(text(envelope,"canonicalAdmission"),ref,clock);
        }catch(Rejected|Unavailable expected){throw expected;}catch(RuntimeException malformed){throw new Unavailable();}
    }
    public void authorize(Reference ref,Admission input,Target target){
        try{
            var body=Map.of("schemaVersion",1,"binding",input.binding(ref),"owner",input.owner(),"target",target);
            var result=post(auth,"auth","/internal/v2/permissions/published-key-query-authorize","auth.published-key-query.authorize",body,false);
            fields(result,"binding","allowed","reason","checks");require(parse(bytes(input.binding(ref)),16384).equals(result.get("binding")));
            var allowed=bool(result,"allowed");var reason=result.get("reason");var checks=result.get("checks");require(reason!=null&&(reason.isNull()||reason.isTextual())&&checks.isArray());
            if(!allowed&&"SUBJECT_INACTIVE".equals(reason.asText())&&checks.isEmpty())throw new Rejected();
            require(checks.size()==2);boolean all=check(checks.get(0),"data_service",input.asset().id(),"execute")&check(checks.get(1),target.resourceType(),target.resourceId(),"query");
            if(allowed){require(all&&reason.isNull());return;}require(!all&&"PERMISSION_DENIED".equals(reason.asText()));throw new Rejected();
        }catch(Rejected|Unavailable expected){throw expected;}catch(RuntimeException malformed){throw new Unavailable();}
    }
    private static boolean check(JsonNode n,String resource,long id,String action){fields(n,"subject","resourceType","resourceId","action","allowed");require("owner".equals(text(n,"subject"))&&resource.equals(text(n,"resourceType"))&&id==number(n,"resourceId")&&action.equals(text(n,"action")));return bool(n,"allowed");}
    private static JsonNode post(SignedHttpClient client,String receiver,String path,String operation,Object body,boolean redeem){
        java.util.concurrent.CompletableFuture<SignedHttpClient.Response> pending=null;
        try{pending=client.sendAsync(new SignedHttpClient.Request(receiver,"POST",path,operation,bytes(body)));var response=pending.get(2500,TimeUnit.MILLISECONDS);
            if(redeem&&Set.of(403,404,409,410).contains(response.status()))throw new Rejected();if(response.status()!=200)throw new Unavailable();return parse(response.body(),65536);
        }catch(Rejected|Unavailable expected){throw expected;}catch(Exception unavailable){if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();throw new Unavailable();}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
}
