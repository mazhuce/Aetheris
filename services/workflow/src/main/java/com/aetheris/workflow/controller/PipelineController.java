package com.aetheris.workflow.controller;

import com.aetheris.workflow.security.GatewayIdentityInterceptor;
import com.aetheris.workflow.service.PipelineExecutionService;
import com.aetheris.workflow.service.WorkflowAuthorizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow/pipelines")
public class PipelineController {
    private final PipelineExecutionService pipelines;
    private final WorkflowAuthorizationService authorization;

    public PipelineController(PipelineExecutionService pipelines, WorkflowAuthorizationService authorization) {
        this.pipelines = pipelines;
        this.authorization = authorization;
    }

    @GetMapping
    public Object list(@RequestParam(defaultValue = "30") int limit,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return pipelines.list(limit, username, authorization.mayManage(username));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.canReadPipeline(username, id)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权查看该流水线"));
        }
        try {
            return ResponseEntity.ok(pipelines.detail(id));
        } catch (IllegalArgumentException missing) {
            return ResponseEntity.notFound().build();
        }
    }

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
