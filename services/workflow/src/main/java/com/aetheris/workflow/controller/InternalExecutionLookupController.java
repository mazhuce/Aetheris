package com.aetheris.workflow.controller;

import com.aetheris.workflow.fixedexecution.*;
import com.aetheris.security.servlet.WorkloadProofFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.Map;

@RestController
public class InternalExecutionLookupController {
    private final WorkflowExecutionStore store;public InternalExecutionLookupController(WorkflowExecutionStore store){this.store=store;}
    @PostMapping("/internal/v2/executions/lookup") public WorkflowExecutionStore.Lookup lookup(HttpServletRequest request,@RequestBody byte[] raw){
        var trusted=WorkloadProofFilter.trusted(request);
        if(!"metadata".equals(trusted.workload().caller())||!"workflow.execution.lookup".equals(trusted.workload().proof().operation())||trusted.user().isPresent())throw new SecurityException();
        var n=FixedJson.parse(raw,65536);FixedJson.fields(n,"instanceId","attemptId","fence");return store.lookup(FixedJson.number(n,"instanceId",1),FixedJson.number(n,"attemptId",1),FixedJson.number(n,"fence",1));
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){return ResponseEntity.status(401).body(Map.of("reason","AUTHENTICATED_METADATA_REQUIRED"));}
    @PostMapping("/internal/v2/quality-parent/lookup") public WorkflowExecutionStore.Lookup qualityParent(HttpServletRequest request,@RequestBody byte[] raw){
        var trusted=WorkloadProofFilter.trusted(request);
        if(!java.util.Set.of("metadata","quality").contains(trusted.workload().caller())||!"workflow.quality-parent.lookup".equals(trusted.workload().proof().operation())||trusted.user().isPresent())throw new SecurityException();
        return store.qualityParent(com.aetheris.security.protocol.execution.WorkflowQualityParent.Reference.parse(raw));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("reason","SOURCE_STORE_UNAVAILABLE"));}
}
