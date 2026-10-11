package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.security.GatewayIdentityInterceptor;
import com.aetheris.ingestion.service.IngestionExecutionService;
import com.aetheris.ingestion.service.IngestionRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 失败运行的显式重放入口；每次重放使用新的幂等键并保留来源运行记录。 */
@RestController
@RequestMapping("/api/ingestion/runs")
public class IngestionRunReplayController {
    private final IngestionRunService runService;
    private final IngestionExecutionService executionService;

    public IngestionRunReplayController(IngestionRunService runService,
                                        IngestionExecutionService executionService) {
        this.runService = runService;
        this.executionService = executionService;
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<?> replay(
            @PathVariable Long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        IngestionRun source = runService.getById(id);
        if (source == null) return ResponseEntity.notFound().build();
        if (!"failed".equals(source.getStatus())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "只有失败运行可以重放"));
        }
        try {
            return ResponseEntity.ok(executionService.execute(
                    source.getTaskId(), username,
                    normalizeReplayKey(requestKey, id), id));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(409).body(java.util.Map.of("message", error.getMessage()));
        } catch (RuntimeException error) {
            return ResponseEntity.status(502).body(java.util.Map.of("message", error.getMessage()));
        }
    }

    private String normalizeReplayKey(String requestKey, Long runId) {
        if (requestKey == null || requestKey.isBlank()) {
            return "replay-" + runId + "-" + UUID.randomUUID();
        }
        return requestKey.trim();
    }
}
