package com.aetheris.auth.fixedexecution;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.audit.PermissionEventRequest;
import com.aetheris.auth.security.AuthSessionValidator;
import com.aetheris.auth.service.PermissionDecisionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

/** Does not authorize arbitrary code: workflow must first verify the sealed definition and resource snapshot. */
@Service
public final class EngineExecutionAuthorizationService {
    private final AuthSessionValidator sessions;
    private final PermissionDecisionService decisions;
    private final PermissionAuditClient audit;

    public EngineExecutionAuthorizationService(AuthSessionValidator sessions,
            PermissionDecisionService decisions, PermissionAuditClient audit) {
        this.sessions = sessions; this.decisions = decisions; this.audit = audit;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Check(String subject, String resourceType, Long resourceId, String action, boolean allowed) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Result(EngineExecutionRequest.Binding binding, boolean allowed, String reason, List<Check> checks) {
        public Result { checks = List.copyOf(checks); }
    }

    public Result authorize(EngineExecutionRequest input) {
        try {
            var actor = input.actor(); var approval = input.approval();
            if (!subjectsActive(input)) {
                return denied(input, "SUBJECT_INACTIVE", List.of());
            }
            long id = input.binding().taskId();
            List<Check> checks = List.of(
                    new Check("actor", "workflow_task", id, "execute",
                            allowed(decisions.check(actor.username(), "workflow_task", id, "execute", Map.of()))),
                    new Check("approval", "workflow_task", id, "update",
                            allowed(decisions.check(approval.username(), "workflow_task", id, "update", Map.of()))),
                    new Check("approval", "platform", null, "manage", allowed(decisions.mayManage(approval.username()))));
            // A name-based permission read must not adopt rights from a replacement account.
            if (!subjectsActive(input)) return denied(input, "SUBJECT_INACTIVE", List.of());
            return checks.stream().allMatch(Check::allowed) ? new Result(input.binding(), true, null, checks)
                    : denied(input, "PERMISSION_DENIED", checks);
        } catch (RuntimeException unavailable) { throw new Unavailable(); }
    }

    private static boolean allowed(PermissionDecisionService.Decision decision) {
        if (decision == null) throw new IllegalStateException();
        return decision.allowed();
    }
    private boolean subjectsActive(EngineExecutionRequest input) {
        var actor=input.actor();var approval=input.approval();
        return sessions.isActive(actor.uid(),actor.username(),actor.accessVersion())
                && sessions.isActive(approval.uid(),approval.username(),approval.accessVersion());
    }
    private Result denied(EngineExecutionRequest input, String reason, List<Check> checks) {
        try {
            audit.recordAsync(new PermissionEventRequest(input.actor().username(), input.actor().uid(),
                    "engine_execution", Long.toString(input.binding().taskId()), "execute", "DENIED",
                    reason, input.actor().requestId()), Duration.ofSeconds(2));
        } catch (RuntimeException ignored) { /* Audit is bounded; denial never becomes an allow. */ }
        return new Result(input.binding(), false, reason, checks);
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("Engine execution authorization unavailable"); }
    }
}
