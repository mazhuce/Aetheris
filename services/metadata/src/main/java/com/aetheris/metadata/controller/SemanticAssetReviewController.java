package com.aetheris.metadata.controller;
import com.aetheris.metadata.entity.SemanticAssetReview;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.SemanticAssetReviewService;
import com.aetheris.metadata.service.MetricDefinitionService;
import com.aetheris.metadata.service.SemanticDimensionService;
import com.aetheris.metadata.service.LogicalModelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/semantic-reviews")
public class SemanticAssetReviewController {
    private final SemanticAssetReviewService service; private final MetadataSecurity security; private final MetricDefinitionService metricService; private final SemanticDimensionService dimensionService; private final LogicalModelService modelService;
    public SemanticAssetReviewController(SemanticAssetReviewService service, MetadataSecurity security, MetricDefinitionService metricService, SemanticDimensionService dimensionService, LogicalModelService modelService) { this.service = service; this.security = security; this.metricService = metricService; this.dimensionService = dimensionService; this.modelService = modelService; }
    @GetMapping public List<SemanticAssetReview> list(HttpServletRequest request, @RequestParam(required=false) String assetType, @RequestParam(required=false) String status) { security.require(request, "metric", null, "publish", Map.of()); return service.list(assetType, status); }
    @PostMapping("/{assetType}/{assetId}") public SemanticAssetReview submit(HttpServletRequest request, @PathVariable String assetType, @PathVariable Long assetId, @RequestBody ReviewRequest body) { String user = security.require(request, resource(assetType), assetId, "update", Map.of()).username(); validate(assetType, assetId); return service.submit(assetType, assetId, user, body == null ? null : body.changeNote(), body == null ? null : body.publishWindowStart(), body == null ? null : body.publishWindowEnd()); }
    @PostMapping("/{id}/approve") public SemanticAssetReview approve(HttpServletRequest request, @PathVariable Long id, @RequestBody ReviewDecision body) { String user = security.require(request, resource(service.require(id).getAssetType()), service.require(id).getAssetId(), "publish", Map.of()).username(); return service.decide(id, user, true, body == null ? null : body.comment()); }
    @PostMapping("/{id}/reject") public SemanticAssetReview reject(HttpServletRequest request, @PathVariable Long id, @RequestBody ReviewDecision body) { String user = security.require(request, resource(service.require(id).getAssetType()), service.require(id).getAssetId(), "publish", Map.of()).username(); return service.decide(id, user, false, body == null ? null : body.comment()); }
    private String resource(String type) { return switch (type) { case "logical_model" -> "model"; case "semantic_dimension" -> "dimension"; case "metric_definition" -> "metric"; default -> throw new IllegalArgumentException("不支持的语义资产类型"); }; }
    private void validate(String type, Long id) { switch (type) { case "logical_model" -> modelService.validate(id); case "semantic_dimension" -> dimensionService.validate(id); case "metric_definition" -> metricService.validate(id); default -> throw new IllegalArgumentException("不支持的语义资产类型"); } }
    public record ReviewRequest(String changeNote, OffsetDateTime publishWindowStart, OffsetDateTime publishWindowEnd) {}
    public record ReviewDecision(String comment) {}
}
