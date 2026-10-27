package com.aetheris.quality.controller;

import com.aetheris.quality.fixedexecution.FixedJson;
import com.aetheris.quality.service.WorkflowChildQualityService;
import com.aetheris.security.protocol.execution.WorkflowQualityParent;
import com.aetheris.security.servlet.WorkloadProofFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/internal/v2/workflow-children")
public class WorkflowChildQualityController {
    private final WorkflowChildQualityService children;
    public WorkflowChildQualityController(WorkflowChildQualityService children){this.children=children;}
    private void trusted(HttpServletRequest request,String operation){var proof=WorkloadProofFilter.trusted(request);if(!"workflow".equals(proof.workload().caller())||!operation.equals(proof.workload().proof().operation())||proof.user().isPresent())throw new SecurityException();}
    @PostMapping("/definition") public WorkflowChildQualityService.Definition definition(HttpServletRequest request,@RequestBody byte[] raw){trusted(request,"quality.workflow-child.definition");var n=FixedJson.parse(raw,65536);FixedJson.fields(n,"ruleId");return children.definition(FixedJson.number(n,"ruleId",1));}
    @PostMapping("/submit") public ResponseEntity<WorkflowChildQualityService.Result> submit(HttpServletRequest request,@RequestBody byte[] raw){trusted(request,WorkflowQualityParent.OPERATION);return ResponseEntity.accepted().body(children.submit(WorkflowQualityParent.Reference.parse(raw)));}
    @PostMapping("/status") public WorkflowChildQualityService.Result status(HttpServletRequest request,@RequestBody byte[] raw){trusted(request,"quality.workflow-child.status");return children.status(WorkflowQualityParent.Reference.parse(raw));}
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){return ResponseEntity.status(401).body(Map.of("reason","AUTHENTICATED_WORKFLOW_REQUIRED"));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("reason","CHILD_STORE_UNAVAILABLE"));}
}
