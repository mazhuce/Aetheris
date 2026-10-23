package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.fixedexecution.*;
import com.aetheris.security.servlet.WorkloadProofFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class InternalExecutionLookupController {
    private final IngestionExecutionStore store;
    public InternalExecutionLookupController(IngestionExecutionStore store){this.store=store;}
    @PostMapping("/internal/v2/executions/lookup")
    public IngestionExecutionStore.Lookup lookup(HttpServletRequest request,@RequestBody byte[] raw) {
        try {var trusted=WorkloadProofFilter.trusted(request);
            if(!"metadata".equals(trusted.workload().caller())||!"ingestion.execution.lookup".equals(trusted.workload().proof().operation())||trusted.user().isPresent())throw new SecurityException();
        }catch(IllegalStateException missing){throw new SecurityException();}
        var body=FixedJson.parse(raw,65536);FixedJson.fields(body,"runId","fence");long fence=FixedJson.number(body,"fence",1);
        if(fence!=1)throw FixedJson.invalid();return store.lookup(FixedJson.number(body,"runId",1),fence);
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> unauthorized(){return ResponseEntity.status(401).body(Map.of("reason","AUTHENTICATED_METADATA_REQUIRED"));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> bad(){return ResponseEntity.badRequest().body(Map.of("reason","INVALID_EXECUTION_LOOKUP"));}
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> stale(){return ResponseEntity.status(409).body(Map.of("reason","SOURCE_EXECUTION_NOT_ACTIVE"));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("reason","SOURCE_STORE_UNAVAILABLE"));}
}
