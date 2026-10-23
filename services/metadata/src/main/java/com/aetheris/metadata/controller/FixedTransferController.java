package com.aetheris.metadata.controller;

import com.aetheris.metadata.fixedexecution.*;
import com.aetheris.security.servlet.WorkloadProofFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class FixedTransferController {
    private final FixedTransferCoordinator coordinator;
    public FixedTransferController(FixedTransferCoordinator coordinator){this.coordinator=coordinator;}
    private void caller(HttpServletRequest request,String operation){
        try{var trusted=WorkloadProofFilter.trusted(request);if(!"ingestion".equals(trusted.workload().caller())||!operation.equals(trusted.workload().proof().operation())||trusted.user().isPresent())throw new SecurityException();}
        catch(IllegalStateException missing){throw new SecurityException();}
    }
    @PostMapping("/internal/v2/executions/transfer/submit") public ResponseEntity<FixedReceiptStore.Receipt> submit(HttpServletRequest request,@RequestBody byte[] raw){caller(request,"metadata.fixed-transfer.submit");return ResponseEntity.accepted().body(coordinator.submit(raw));}
    @PostMapping("/internal/v2/executions/quality-query/submit") public ResponseEntity<FixedReceiptStore.Receipt> quality(HttpServletRequest request,@RequestBody byte[] raw){
        var operation=statusCaller(request,"metadata.fixed-quality-query.submit");if(operation!=FixedExecutionOperation.QUALITY)throw new SecurityException();return ResponseEntity.accepted().body(coordinator.submit(operation,raw));
    }
    @PostMapping("/internal/v2/executions/status") public FixedReceiptStore.Receipt status(HttpServletRequest request,@RequestBody byte[] raw){return coordinator.status(statusCaller(request,"metadata.fixed-execution.status"),raw);}
    private FixedExecutionOperation statusCaller(HttpServletRequest request,String operation){
        try{var trusted=WorkloadProofFilter.trusted(request);if(!operation.equals(trusted.workload().proof().operation())||trusted.user().isPresent())throw new SecurityException();return FixedExecutionOperation.caller(trusted.workload().caller());}
        catch(IllegalStateException missing){throw new SecurityException();}
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> unauthenticated(){return error(401,"AUTHENTICATED_INGESTION_REQUIRED");}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(){return error(400,"INVALID_FIXED_EXECUTION_REQUEST");}
    @ExceptionHandler(FixedReceiptStore.Conflict.class) ResponseEntity<?> conflict(){return error(409,"EXECUTION_BINDING_CONFLICT");}
    @ExceptionHandler(FixedTransferCoordinator.NotFound.class) ResponseEntity<?> missing(){return error(404,"RECEIPT_NOT_FOUND");}
    @ExceptionHandler(FixedTransferPeers.Rejected.class) ResponseEntity<?> denied(){return error(403,"EXECUTION_NOT_ADMITTED");}
    @ExceptionHandler({FixedTransferPeers.Unavailable.class,FixedReceiptStore.Unavailable.class,org.springframework.dao.DataAccessException.class}) ResponseEntity<?> unavailable(){return error(503,"EXECUTION_ADMISSION_UNAVAILABLE");}
    private ResponseEntity<?> error(int status,String code){return ResponseEntity.status(status).body(Map.of("reason",code));}
}
