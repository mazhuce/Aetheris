package com.aetheris.auth.controller;

import com.aetheris.auth.publishedkey.*;
import com.aetheris.security.servlet.WorkloadProofFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public final class PublishedKeyAuthorizationController {
    public static final String PATH="/internal/v2/permissions/published-key-query-authorize",OPERATION="auth.published-key-query.authorize";
    private final PublishedKeyAuthorizationService authorization;
    public PublishedKeyAuthorizationController(PublishedKeyAuthorizationService authorization){this.authorization=authorization;}
    @PostMapping(PATH) public PublishedKeyAuthorizationService.Result authorize(HttpServletRequest request,@RequestBody byte[] raw){
        try{var trusted=WorkloadProofFilter.trusted(request);
            if(!"metadata".equals(trusted.workload().caller())||!OPERATION.equals(trusted.workload().proof().operation())||trusted.user().isPresent())throw new SecurityException();
        }catch(IllegalStateException missing){throw new SecurityException();}
        return authorization.authorize(PublishedKeyRequest.parse(raw));
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> unauthorized(){return ResponseEntity.status(401).body(Map.of("reason","AUTHENTICATED_METADATA_REQUIRED"));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Map.of("reason","INVALID_PUBLISHED_KEY_ASSERTION"));}
    @ExceptionHandler(PublishedKeyAuthorizationService.Unavailable.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("reason","AUTH_UNAVAILABLE"));}
}
