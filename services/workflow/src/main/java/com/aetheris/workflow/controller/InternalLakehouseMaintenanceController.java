package com.aetheris.workflow.controller;

import com.aetheris.workflow.service.ExecutionAdapter.RemoteExecution;
import com.aetheris.workflow.service.LakehouseMaintenanceExecutionService;
import com.aetheris.workflow.service.LakehouseMaintenanceExecutionService.SubmitRequest;
import com.aetheris.workflow.service.SparkRunnerException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Internal metadata-to-workflow contract for Iceberg maintenance execution. */
@RestController
@RequestMapping("/internal/lakehouse/maintenance")
public class InternalLakehouseMaintenanceController {
    private final LakehouseMaintenanceExecutionService service;
    private final String internalToken;

    public InternalLakehouseMaintenanceController(
            LakehouseMaintenanceExecutionService service,
            @Value("${aetheris.internal-token:}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submit(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                    @RequestBody SubmitRequest request) {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "内部令牌无效"));
        try {
            return ResponseEntity.accepted().body(service.submit(request));
        } catch (RuntimeException error) {
            return error(error);
        }
    }

    @GetMapping("/status/{requestKey}")
    public ResponseEntity<?> status(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                    @PathVariable String requestKey) {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "内部令牌无效"));
        try {
            return service.status(requestKey).<ResponseEntity<?>>map(value -> ResponseEntity.ok(value))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (RuntimeException error) {
            return error(error);
        }
    }

    @DeleteMapping("/jobs/{remoteHandle}")
    public ResponseEntity<?> cancel(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                    @PathVariable String remoteHandle) {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "内部令牌无效"));
        try {
            RemoteExecution execution = service.cancel(remoteHandle);
            return ResponseEntity.ok(execution);
        } catch (RuntimeException error) {
            return error(error);
        }
    }

    private boolean authorized(String token) {
        return !internalToken.isBlank() && token != null && java.security.MessageDigest.isEqual(
                internalToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ResponseEntity<Map<String, String>> error(RuntimeException error) {
        String message = error.getMessage() == null || error.getMessage().isBlank() ? "维护执行失败" : error.getMessage();
        int status = error instanceof SparkRunnerException runner
                ? Math.clamp(runner.getStatusCode(), 400, 599) : HttpStatus.BAD_REQUEST.value();
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
