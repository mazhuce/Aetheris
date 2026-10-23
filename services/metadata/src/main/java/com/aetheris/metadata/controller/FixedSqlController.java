package com.aetheris.metadata.controller;
import com.aetheris.metadata.fixedexecution.*;
import com.aetheris.metadata.service.SqlWriteGuard;
import com.aetheris.security.servlet.WorkloadProofFilter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

/** No datasource, JDBC, request identity or credential is accepted by the pure policy route. */
@RestController
public class FixedSqlController {
    private final FixedTransferCoordinator coordinator;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(512*1024).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public FixedSqlController(FixedTransferCoordinator coordinator){this.coordinator=coordinator;}
    private void caller(HttpServletRequest request,String operation){var trusted=WorkloadProofFilter.trusted(request);if(!"workflow".equals(trusted.workload().caller())||!operation.equals(trusted.workload().proof().operation())||trusted.user().isPresent())throw new SecurityException();}
    @PostMapping("/internal/v2/executions/sql/submit") public ResponseEntity<FixedReceiptStore.Receipt> submit(HttpServletRequest request,@RequestBody byte[] raw){caller(request,"metadata.fixed-sql.submit");return ResponseEntity.accepted().body(coordinator.submit(FixedExecutionOperation.SQL,raw));}
    @JsonInclude(JsonInclude.Include.ALWAYS) public record Validation(boolean valid,String reason){}
    @PostMapping("/internal/v2/sql/validate-write") public Validation validate(HttpServletRequest request,@RequestBody byte[] raw){
        caller(request,"metadata.fixed-sql.validate-write");if(raw==null||raw.length==0||raw.length>1024*1024)throw new IllegalArgumentException();
        JsonNode n;try{n=JSON.readTree(raw);}catch(java.io.IOException invalid){throw new IllegalArgumentException();}
        if(n==null||!n.isObject()||n.size()!=1||!n.has("sql")||!n.get("sql").isTextual())throw new IllegalArgumentException();
        try{SqlWriteGuard.validate(n.get("sql").textValue());return new Validation(true,null);}catch(IllegalArgumentException rejected){return new Validation(false,"SQL_POLICY_REJECTED");}
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){return error(401,"AUTHENTICATED_WORKFLOW_REQUIRED");}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(){return error(400,"INVALID_FIXED_SQL_REQUEST");}
    @ExceptionHandler(FixedReceiptStore.Conflict.class) ResponseEntity<?> conflict(){return error(409,"EXECUTION_BINDING_CONFLICT");}
    @ExceptionHandler(FixedTransferPeers.Rejected.class) ResponseEntity<?> rejected(){return error(403,"EXECUTION_NOT_ADMITTED");}
    @ExceptionHandler({FixedTransferPeers.Unavailable.class,FixedReceiptStore.Unavailable.class,org.springframework.dao.DataAccessException.class}) ResponseEntity<?> unavailable(){return error(503,"EXECUTION_ADMISSION_UNAVAILABLE");}
    private ResponseEntity<?> error(int status,String reason){return ResponseEntity.status(status).body(Map.of("reason",reason));}
}
