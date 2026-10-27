package com.aetheris.auth.controller;

import com.aetheris.auth.fixedexecution.*;
import com.aetheris.security.servlet.WorkloadProofFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** A separate workflow-only capability; metadata fixed-execution authorization stays metadata-only. */
@RestController
public final class EngineExecutionAuthorizationController {
    public static final String PATH = "/internal/v2/permissions/engine-execution-authorize";
    public static final String OPERATION = "auth.engine-execution.authorize";
    private final EngineExecutionAuthorizationService authorization;
    public EngineExecutionAuthorizationController(EngineExecutionAuthorizationService authorization) {
        this.authorization = authorization;
    }
    @PostMapping(PATH)
    public EngineExecutionAuthorizationService.Result authorize(HttpServletRequest request, @RequestBody byte[] raw) {
        try {
            var trusted = WorkloadProofFilter.trusted(request);
            if (!"workflow".equals(trusted.workload().caller())
                    || !OPERATION.equals(trusted.workload().proof().operation()) || trusted.user().isPresent()) {
                throw new SecurityException();
            }
        } catch (IllegalStateException absent) { throw new SecurityException(); }
        return authorization.authorize(EngineExecutionRequest.parse(raw));
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(401).body(Map.of("reason", "AUTHENTICATED_WORKFLOW_REQUIRED"));
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("reason", "INVALID_ENGINE_EXECUTION_ASSERTION"));
    }
    @ExceptionHandler(EngineExecutionAuthorizationService.Unavailable.class) ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("reason", "AUTH_UNAVAILABLE"));
    }
}
