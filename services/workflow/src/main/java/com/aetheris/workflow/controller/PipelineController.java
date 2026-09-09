package com.aetheris.workflow.controller;

import com.aetheris.workflow.security.GatewayIdentityInterceptor;
import com.aetheris.workflow.service.PipelineExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow/pipelines")
public class PipelineController {
    private final PipelineExecutionService pipelines;

    public PipelineController(PipelineExecutionService pipelines) { this.pipelines = pipelines; }

    @GetMapping
    public Object list(@RequestParam(defaultValue = "30") int limit) { return pipelines.list(limit); }

    @GetMapping("/{id}")
    public Object detail(@PathVariable long id) { return pipelines.detail(id); }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody StartRequest request,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        try { return ResponseEntity.ok(pipelines.start(request.targetTaskId(), request.bizDate(), username)); }
        catch (IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", error.getMessage())); }
        catch (IllegalStateException error) { return ResponseEntity.status(409).body(Map.of("message", error.getMessage())); }
    }

    @PostMapping("/waivers")
    public ResponseEntity<?> waive(@RequestBody WaiverRequest request,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        try {
            return ResponseEntity.ok(pipelines.createWaiver(request.qualityTaskId(), request.bizDate(),
                    request.reason(), request.expiresAt(), username));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
    }

    public record StartRequest(long targetTaskId, String bizDate) { }
    public record WaiverRequest(long qualityTaskId, String bizDate, String reason, OffsetDateTime expiresAt) { }
}
