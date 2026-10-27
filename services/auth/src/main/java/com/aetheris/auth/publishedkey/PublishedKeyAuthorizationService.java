package com.aetheris.auth.publishedkey;

import com.aetheris.auth.audit.*;
import com.aetheris.auth.security.AuthSessionValidator;
import com.aetheris.auth.service.PermissionDecisionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

@Service
public final class PublishedKeyAuthorizationService {
    private final AuthSessionValidator sessions;private final PermissionDecisionService decisions;private final PermissionAuditClient audit;
    public PublishedKeyAuthorizationService(AuthSessionValidator sessions,PermissionDecisionService decisions,PermissionAuditClient audit){this.sessions=sessions;this.decisions=decisions;this.audit=audit;}
    public record Check(String subject,String resourceType,long resourceId,String action,boolean allowed){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Result(PublishedKeyRequest.Binding binding,boolean allowed,String reason,List<Check> checks){public Result{checks=List.copyOf(checks);}}
    public Result authorize(PublishedKeyRequest input){
        try{
            var owner=input.owner();
            if(!sessions.isActive(owner.uid(),owner.username(),owner.accessVersion()))return denied(input,"SUBJECT_INACTIVE",List.of());
            var asset=decisions.check(owner.username(),"data_service",input.binding().assetId(),"execute",Map.of());
            var target=decisions.check(owner.username(),input.target().resourceType(),input.target().resourceId(),"query",input.target().context());
            if(asset==null||target==null)throw new IllegalStateException();
            var checks=List.of(new Check("owner","data_service",input.binding().assetId(),"execute",asset.allowed()),new Check("owner",input.target().resourceType(),input.target().resourceId(),"query",target.allowed()));
            if(!sessions.isActive(owner.uid(),owner.username(),owner.accessVersion()))return denied(input,"SUBJECT_INACTIVE",List.of());
            return asset.allowed()&&target.allowed()?new Result(input.binding(),true,null,checks):denied(input,"PERMISSION_DENIED",checks);
        }catch(RuntimeException unavailable){throw new Unavailable();}
    }
    private Result denied(PublishedKeyRequest input,String reason,List<Check> checks){
        try{audit.recordAsync(new PermissionEventRequest(input.owner().username(),input.owner().uid(),"data_service",Long.toString(input.binding().assetId()),"execute","DENIED",reason,input.binding().requestId()),Duration.ofSeconds(2));}
        catch(RuntimeException ignored){/* The bounded audit side channel cannot change a permission decision. */}
        return new Result(input.binding(),false,reason,checks);
    }
    public static final class Unavailable extends RuntimeException{public Unavailable(){super("Published key authorization unavailable");}}
}
