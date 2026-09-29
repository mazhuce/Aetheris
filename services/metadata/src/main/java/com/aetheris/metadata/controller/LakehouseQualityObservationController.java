package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.LakehouseQualityObservation;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.LakehouseQualityGate;
import com.aetheris.metadata.service.LakehouseQualityObservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/lakehouse")
public class LakehouseQualityObservationController {
    private final LakehouseQualityObservationService service;
    private final MetadataSecurity security;

    public LakehouseQualityObservationController(LakehouseQualityObservationService service, MetadataSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping("/tables/{tableId}/quality-observations")
    public List<LakehouseQualityObservation> latest(HttpServletRequest request, @PathVariable Long tableId) {
        security.require(request, "lakehouse_table", tableId, "view", Map.of());
        return service.latestForTable(tableId);
    }

    @PostMapping("/tables/{tableId}/quality-observations")
    public ResponseEntity<LakehouseQualityObservation> record(HttpServletRequest request, @PathVariable Long tableId,
                                                               @Valid @RequestBody LakehouseQualityObservation observation) {
        var identity = security.require(request, "lakehouse_table", tableId, "update", Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.record(tableId, observation, identity.username()));
    }

    @GetMapping("/tables/{tableId}/gold-quality-gate")
    public LakehouseQualityGate.GateResult goldGate(HttpServletRequest request, @PathVariable Long tableId) {
        security.require(request, "lakehouse_table", tableId, "view", Map.of());
        return service.goldGate(tableId);
    }
}
