package com.aetheris.serving.controller;

import com.aetheris.serving.service.PublishedKeyAdmissionService;
import com.aetheris.security.servlet.WorkloadProofFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class PublishedKeyAdmissionController {
    private final PublishedKeyAdmissionService admissions;
    public PublishedKeyAdmissionController(PublishedKeyAdmissionService admissions){this.admissions=admissions;}
    @PostMapping("/internal/v2/published-key-admissions/redeem")
    public PublishedKeyAdmissionService.Redeemed redeem(HttpServletRequest request,@RequestBody byte[] bytes){
        try{var trusted=WorkloadProofFilter.trusted(request);if(!trusted.workload().caller().equals("metadata")||!trusted.workload().proof().operation().equals("serving.published-key.redeem")||trusted.user().isPresent())throw new SecurityException();}
        catch(IllegalStateException missing){throw new SecurityException();}
        return admissions.redeem(bytes);
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){return ResponseEntity.status(401).body(Map.of("message","Authenticated metadata required"));}
    @ExceptionHandler(PublishedKeyAdmissionService.Conflict.class) ResponseEntity<?> conflict(){return ResponseEntity.status(409).body(Map.of("message","Admission unavailable"));}
    @ExceptionHandler(PublishedKeyAdmissionService.NotFound.class) ResponseEntity<?> missing(){return ResponseEntity.status(404).body(Map.of("message","Admission unavailable"));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Map.of("message","Invalid admission request"));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("message","Admission unavailable"));}
}
