package com.aetheris.auth.controller;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.audit.PermissionEventRequest;
import com.aetheris.auth.dto.*;
import com.aetheris.auth.security.AuthSessionValidator;
import com.aetheris.auth.config.AuthV2SecurityConfiguration;
import com.aetheris.auth.service.PermissionDecisionService;
import com.aetheris.auth.service.PermissionVersionService;
import com.aetheris.auth.web.AccessDeniedException;
import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.security.servlet.WorkloadProofFilter;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

/** Bodies contain resource context only. The authenticated forwarded proof is the sole subject. */
@RestController
@RequestMapping("/internal/v2")
public class InternalV2Controller {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT, DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    static {
        var textual=JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for(var shape:List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
            textual.setCoercion(shape,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    }
    private final AuthSessionValidator sessions;
    private final PermissionDecisionService decisions;
    private final PermissionVersionService versions;
    private final PermissionAuditClient audit;
    private final Validator validator;
    public InternalV2Controller(AuthSessionValidator sessions, PermissionDecisionService decisions,
                                PermissionVersionService versions, PermissionAuditClient audit, Validator validator) {
        this.sessions=sessions; this.decisions=decisions; this.versions=versions; this.audit=audit; this.validator=validator;
    }

    public record SessionBody(@NotNull @Positive Long uid, @NotBlank @Size(max=64) String username,
                              @NotNull @PositiveOrZero Long accessVersion) { }
    public record CheckBody(@NotBlank @Size(max=32) String resourceType, @Positive Long resourceId,
                            @NotBlank @Size(max=32) String action, Map<String,String> ctx) { }
    public record VisibilityBody(@NotBlank @Size(max=32) String resourceType, @NotBlank @Size(max=32) String action) { }
    public record BatchItem(@NotNull @Positive Long resourceId, Map<String,String> ctx) { }
    public record BatchBody(@NotBlank @Size(max=32) String resourceType, @NotBlank @Size(max=32) String action,
                            @NotNull @Size(max=1000) List<@NotNull @Valid BatchItem> items) { }
    public record MutateBody(@NotBlank @Size(max=32) String service) { }
    public record EmptyBody() { }

    @PostMapping("/sessions/check")
    public Map<String,Boolean> session(@RequestBody byte[] raw) {
        SessionBody body=read(raw,SessionBody.class);
        return Map.of("active",sessions.isActive(body.uid(),body.username(),body.accessVersion()));
    }
    @PostMapping("/permissions/check")
    public PermissionCheckResponse check(@RequestBody byte[] raw,HttpServletRequest request) {
        CheckBody body=read(raw,CheckBody.class); var subject=subject(request);
        resource(request,body.resourceType(),body.action());
        var decision=decisions.check(subject.proof().username(),body.resourceType(),body.resourceId(),body.action(),ctx(body.ctx()));
        denied(subject,body.resourceType(),body.resourceId()==null?null:body.resourceId().toString(),body.action(),decision);
        return new PermissionCheckResponse(decision.allowed(),decision.reason());
    }
    @PostMapping("/permissions/visibility")
    public VisibilityResponse visibility(@RequestBody byte[] raw,HttpServletRequest request) {
        VisibilityBody body=read(raw,VisibilityBody.class); var user=subject(request);
        resource(request,body.resourceType(),body.action());
        var result=decisions.visibility(user.proof().username(),body.resourceType(),body.action());
        return new VisibilityResponse(result.mode().name(),result.ids());
    }
    @PostMapping("/permissions/check-batch")
    public BatchPermissionCheckResponse batch(@RequestBody byte[] raw,HttpServletRequest request) {
        BatchBody body=read(raw,BatchBody.class); var user=subject(request);
        resource(request,body.resourceType(),body.action());
        Map<Long,Map<String,String>> items=new LinkedHashMap<>();
        for(var item:body.items()) {
            if(items.putIfAbsent(item.resourceId(),ctx(item.ctx()))!=null) throw new IllegalArgumentException("Duplicate resource ID");
        }
        return new BatchPermissionCheckResponse(new ArrayList<>(decisions.allowedIds(user.proof().username(),body.resourceType(),body.action(),items)));
    }
    @PostMapping("/permissions/may-manage")
    public PermissionCheckResponse manage(@RequestBody byte[] raw,HttpServletRequest request) {
        read(raw,EmptyBody.class); var user=subject(request); var result=decisions.mayManage(user.proof().username());
        denied(user,"service","manage","admin",result); return new PermissionCheckResponse(result.allowed(),result.reason());
    }
    @PostMapping("/permissions/may-mutate")
    public PermissionCheckResponse mutate(@RequestBody byte[] raw,HttpServletRequest request) {
        MutateBody body=read(raw,MutateBody.class); var user=subject(request);
        if(!AuthV2SecurityConfiguration.mutationAllowed(caller(request),body.service())) throw new AccessDeniedException("Caller service is not allowed");
        var result=decisions.mayMutate(user.proof().username(),body.service());
        denied(user,"service",body.service(),"mutate",result); return new PermissionCheckResponse(result.allowed(),result.reason());
    }
    @PostMapping("/permissions/version")
    public Map<String,Long> version(@RequestBody byte[] raw) {
        read(raw,EmptyBody.class); return Map.of("version",versions.current());
    }
    private TrustedUser subject(HttpServletRequest request) {
        if(!(request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser user)) throw new AccessDeniedException("Missing authenticated user");
        var proof=user.proof();
        if(!sessions.isActive(proof.uid(),proof.username(),proof.accessVersion())) throw new AccessDeniedException("Inactive session");
        return user;
    }
    private static String caller(HttpServletRequest request) { return WorkloadProofFilter.trusted(request).workload().caller(); }
    private static void resource(HttpServletRequest request,String type,String action) {
        if(!AuthV2SecurityConfiguration.resourceAllowed(caller(request),type,action)) throw new AccessDeniedException("Caller resource is not allowed");
    }
    private <T> T read(byte[] bytes,Class<T> type) {
        try {
            T value=JSON.readValue(bytes,type);
            if(value==null || !validator.validate(value).isEmpty()) throw new IllegalArgumentException("Invalid v2 request");
            return value;
        }catch(IOException failure) { throw new IllegalArgumentException("Invalid v2 request"); }
    }
    private static Map<String,String> ctx(Map<String,String> input) {
        if(input==null) return Map.of();
        if(input.size()>32 || input.entrySet().stream().anyMatch(e->e.getKey()==null || e.getKey().length()>64 || e.getValue()==null || e.getValue().length()>256))
            throw new IllegalArgumentException("Invalid resource context");
        return Map.copyOf(input);
    }
    private void denied(TrustedUser user,String type,String id,String action,PermissionDecisionService.Decision result) {
        if(!result.allowed()) audit.recordAsync(new PermissionEventRequest(user.proof().username(),null,type,id,action,
                "DENIED",result.reason(),user.proof().requestId()),Duration.ofSeconds(2));
    }
}
