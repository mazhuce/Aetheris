package com.aetheris.auth.fixedexecution;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.audit.PermissionEventRequest;
import com.aetheris.auth.security.AuthSessionValidator;
import com.aetheris.auth.service.PermissionDecisionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

/** Authorizes only the fixed dependency set; metadata is responsible for trusted source evidence. */
@Service
public final class FixedExecutionAuthorizationService {
    private final AuthSessionValidator sessions;
    private final PermissionDecisionService decisions;
    private final PermissionAuditClient audit;
    public FixedExecutionAuthorizationService(AuthSessionValidator sessions,PermissionDecisionService decisions,PermissionAuditClient audit) {
        this.sessions=sessions;this.decisions=decisions;this.audit=audit;
    }
    public record Check(String subject,String resourceType,long resourceId,String action,boolean allowed) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Result(FixedExecutionRequest.Binding binding,boolean allowed,String reason,List<Check> checks) {
        public Result {checks=List.copyOf(checks);}
    }
    public Result authorize(FixedExecutionRequest input) {
        try {
            var actor=input.actor();var approval=input.approval();var parent=input.parent();
            if(!sessions.isActive(actor.uid(),actor.username(),actor.accessVersion())
                    || !sessions.isActive(approval.uid(),approval.username(),approval.accessVersion())
                    || (parent!=null && !sessions.isActive(parent.approval().uid(),parent.approval().username(),parent.approval().accessVersion())))
                return denied(input,"SUBJECT_INACTIVE",List.of());
            List<Check> checks=new ArrayList<>();
            String resource=switch(input.binding().origin()) {
                case "workflow" -> "workflow_task";case "ingestion" -> "ingestion_task";case "quality" -> "quality_rule";
                default -> throw new IllegalArgumentException();
            };
            check(checks,"actor",actor.username(),resource,input.binding().resourceId(),"execute");
            check(checks,"approval",approval.username(),"datasource",input.targets().sourceDatasourceId(),"query");
            if(input.targets().targetDatasourceId()!=null)
                check(checks,"approval",approval.username(),"datasource",input.targets().targetDatasourceId(),"write");
            if(parent!=null) {
                check(checks,"actor",actor.username(),"workflow_task",parent.resourceId(),"execute");
                check(checks,"parentApproval",parent.approval().username(),"quality_rule",input.binding().resourceId(),"execute");
            }
            return checks.stream().allMatch(Check::allowed) ? new Result(input.binding(),true,null,checks)
                    : denied(input,"PERMISSION_DENIED",checks);
        }catch(RuntimeException failure){throw new Unavailable();}
    }
    private void check(List<Check> checks,String subject,String username,String type,long id,String action) {
        var decision=decisions.check(username,type,id,action,Map.of());
        if(decision==null)throw new IllegalStateException();
        checks.add(new Check(subject,type,id,action,decision.allowed()));
    }
    private Result denied(FixedExecutionRequest input,String reason,List<Check> checks) {
        try {audit.recordAsync(new PermissionEventRequest(input.actor().username(),input.actor().uid(),
                "fixed_execution",Long.toString(input.binding().resourceId()),"execute","DENIED",reason,input.actor().requestId()),Duration.ofSeconds(2));}
        catch(RuntimeException ignored){/* Bounded best-effort side channel cannot affect the decision. */}
        return new Result(input.binding(),false,reason,checks);
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable(){super("Fixed execution authorization unavailable");}
    }
}
