package com.aetheris.auth.controller;

import com.aetheris.auth.fixedexecution.*;
import com.aetheris.security.servlet.WorkloadProofFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Metadata-only fixed invocation authorization. Neither legacy headers nor a USER can substitute. */
@RestController
public final class FixedExecutionAuthorizationController {
    public static final String PATH="/internal/v2/permissions/fixed-execution-authorize";
    public static final String OPERATION="auth.fixed-execution.authorize";
    private final FixedExecutionAuthorizationService authorization;
    public FixedExecutionAuthorizationController(FixedExecutionAuthorizationService authorization){this.authorization=authorization;}
    @PostMapping(PATH)
    public FixedExecutionAuthorizationService.Result authorize(HttpServletRequest request,@RequestBody byte[] raw) {
        try {
            var trusted=WorkloadProofFilter.trusted(request);
            if(!"metadata".equals(trusted.workload().caller()) || !OPERATION.equals(trusted.workload().proof().operation())
                    || trusted.user().isPresent())throw new SecurityException();
        }catch(IllegalStateException missing){throw new SecurityException();}
        return authorization.authorize(FixedExecutionRequest.parse(raw));
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> unauthenticated(){
        return ResponseEntity.status(401).body(Map.of("reason","AUTHENTICATED_METADATA_REQUIRED"));
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(){
        return ResponseEntity.badRequest().body(Map.of("reason","INVALID_FIXED_EXECUTION_ASSERTION"));
    }
    @ExceptionHandler(FixedExecutionAuthorizationService.Unavailable.class) ResponseEntity<?> unavailable(){
        return ResponseEntity.status(503).body(Map.of("reason","AUTH_UNAVAILABLE"));
    }
}
