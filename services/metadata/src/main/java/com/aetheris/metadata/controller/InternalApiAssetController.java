package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.ApiAsset;
import com.aetheris.metadata.service.ApiAssetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Service-to-service lookup for the published, executable API contract. */
@RestController
@RequestMapping("/internal/api-assets")
public class InternalApiAssetController {
    private final ApiAssetService service;

    public InternalApiAssetController(ApiAssetService service) { this.service = service; }

    @GetMapping("/{code}")
    public ResponseEntity<Map<String, Object>> published(@PathVariable String code) {
        ApiAsset asset = service.publishedByCode(code);
        if (asset == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "id", asset.getId(),
                "assetCode", asset.getAssetCode(),
                "version", asset.getVersion(),
                "targetType", asset.getTargetType(),
                "targetRef", asset.getTargetRef()));
    }
}
