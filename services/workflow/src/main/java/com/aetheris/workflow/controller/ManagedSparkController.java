package com.aetheris.workflow.controller;

import com.aetheris.workflow.security.GatewayIdentityInterceptor;
import com.aetheris.workflow.service.ExecutionAdapter;
import com.aetheris.workflow.service.ManagedSparkCatalogService;
import com.aetheris.workflow.service.ManagedSparkCatalogService.ArtifactInput;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobInput;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/** Managed Spark environment, artifact and versioned job-definition endpoints. */
@RestController
@RequestMapping("/api/workflow/spark")
public class ManagedSparkController {

    private final ManagedSparkCatalogService catalog;
    private final ExecutionAdapter adapter;

    public ManagedSparkController(ManagedSparkCatalogService catalog,
                                  com.aetheris.workflow.service.SparkBatchExecutionAdapter adapter) {
        this.catalog = catalog;
        this.adapter = adapter;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        try {
            return ResponseEntity.ok(adapter.health());
        } catch (RuntimeException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "message", error.getMessage()));
        }
    }

    @GetMapping("/environments")
    public Object environments() { return catalog.environments(); }

    @GetMapping("/artifacts")
    public Object artifacts() { return catalog.artifacts(); }

    @PostMapping("/artifacts")
    public ResponseEntity<?> register(
            @RequestBody ArtifactInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.registerArtifact(input, username));
    }

    @PostMapping("/artifacts/{id}/approve")
    public ResponseEntity<?> approve(
            @PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.approveArtifact(id, username));
    }

    @PostMapping("/artifacts/{id}/revoke")
    public ResponseEntity<?> revoke(@PathVariable long id) {
        return action(() -> catalog.revokeArtifact(id));
    }

    @GetMapping("/jobs")
    public Object jobs() { return catalog.jobs(); }

    @PostMapping("/jobs")
    public ResponseEntity<?> createJob(
            @RequestBody SparkJobInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.createJob(input, username));
    }

    @PostMapping("/jobs/{id}/publish")
    public ResponseEntity<?> publishJob(
            @PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.publishJob(id, username));
    }

    @PostMapping("/jobs/{id}/disable")
    public ResponseEntity<?> disableJob(@PathVariable long id) {
        return action(() -> catalog.disableJob(id));
    }

    private ResponseEntity<?> action(Supplier<?> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage()));
        }
    }
}
