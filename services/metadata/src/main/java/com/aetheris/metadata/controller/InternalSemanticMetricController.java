package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.MetricQueryRequest;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.service.MetricDefinitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Compose 内部语义接口，供 serving 与 ai-agent 复用同一口径。 */
@RestController
@RequestMapping("/internal/semantic/metrics")
public class InternalSemanticMetricController {
    private final MetricDefinitionService service;
    public InternalSemanticMetricController(MetricDefinitionService service) { this.service = service; }
    @GetMapping public List<MetricDefinition> list(@RequestParam(required = false) String keyword) { return service.published(keyword); }
    @PostMapping("/{code}/query") public Map<String, Object> query(@PathVariable String code,
            @Valid @RequestBody(required = false) MetricQueryRequest request) { return service.query(code, request); }

    @ExceptionHandler(MetricDefinitionService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
