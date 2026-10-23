package com.aetheris.quality.controller;

import com.aetheris.quality.security.GatewayIdentityInterceptor;
import com.aetheris.quality.service.ExecutionRecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quality/execution-recovery")
public class ExecutionRecoveryController {
    private final ExecutionRecoveryService recovery;
    private final RecoveryAdminGate gate;
    public ExecutionRecoveryController(ExecutionRecoveryService recovery,RecoveryAdminGate gate) {
        this.recovery=recovery; this.gate=gate;
    }
    @GetMapping
    public List<ExecutionRecoveryService.PendingResult> pending(@RequestParam(defaultValue="50") int limit,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        gate.requireManage(username);
        return recovery.pending(limit);
    }
    @PostMapping("/{id}/reconcile")
    public ResponseEntity<?> reconcile(@PathVariable Long id,@RequestBody Reconciliation request,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        gate.requireManage(username);
        try {
            recovery.reconcile(id,request.status(),request.confirmedRemoteStopped(),request.evidence(),username,request.violations());
            return ResponseEntity.noContent().build();
        } catch(IllegalArgumentException invalid) { return ResponseEntity.badRequest().body(Map.of("message",invalid.getMessage())); }
        catch(IllegalStateException conflict) { return ResponseEntity.status(409).body(Map.of("message",conflict.getMessage())); }
    }
    @GetMapping("/{id}")
    public ExecutionRecoveryService.Definition detail(@PathVariable Long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        gate.requireManage(username); return recovery.detail(id);
    }
    public record Reconciliation(String status,boolean confirmedRemoteStopped,String evidence,Long violations) { }
}
