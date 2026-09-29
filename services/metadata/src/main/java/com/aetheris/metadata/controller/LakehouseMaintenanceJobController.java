package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.LakehouseMaintenanceJob;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.LakehouseMaintenanceJobService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/lakehouse")
public class LakehouseMaintenanceJobController {
    private final LakehouseMaintenanceJobService service;
    private final MetadataSecurity security;

    public LakehouseMaintenanceJobController(LakehouseMaintenanceJobService service, MetadataSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping("/tables/{tableId}/maintenance-jobs")
    public List<LakehouseMaintenanceJob> list(HttpServletRequest request, @PathVariable Long tableId) {
        security.require(request, "lakehouse_table", tableId, "view", Map.of());
        return service.listForTable(tableId);
    }

    @PostMapping("/tables/{tableId}/maintenance-jobs")
    public ResponseEntity<LakehouseMaintenanceJob> request(HttpServletRequest request, @PathVariable Long tableId,
                                                            @Valid @RequestBody LakehouseMaintenanceJob job) {
        var identity = security.require(request, "lakehouse_table", tableId, "update", Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.request(tableId, job, identity.username()));
    }

    @PostMapping("/maintenance-jobs/{jobId}/claim")
    public LakehouseMaintenanceJob claim(HttpServletRequest request, @PathVariable Long jobId) {
        security.require(request, "lakehouse_table", null, "update", Map.of());
        return service.claim(jobId);
    }

    @PostMapping("/maintenance-jobs/{jobId}/execute")
    public ResponseEntity<LakehouseMaintenanceJob> execute(HttpServletRequest request, @PathVariable Long jobId) {
        security.require(request, "lakehouse_table", null, "update", Map.of());
        return ResponseEntity.accepted().body(service.execute(jobId));
    }

    @PostMapping("/maintenance-jobs/{jobId}/reconcile")
    public LakehouseMaintenanceJob reconcile(HttpServletRequest request, @PathVariable Long jobId) {
        security.require(request, "lakehouse_table", null, "update", Map.of());
        return service.reconcile(jobId);
    }

    @PostMapping("/maintenance-jobs/{jobId}/complete")
    public LakehouseMaintenanceJob complete(HttpServletRequest request, @PathVariable Long jobId,
                                             @RequestBody Map<String, String> body) {
        security.require(request, "lakehouse_table", null, "update", Map.of());
        return service.complete(jobId, body == null ? null : body.get("status"),
                body == null ? null : body.get("resultJson"), body == null ? null : body.get("error"));
    }

    @PostMapping("/maintenance-jobs/{jobId}/cancel")
    public LakehouseMaintenanceJob cancel(HttpServletRequest request, @PathVariable Long jobId) {
        security.require(request, "lakehouse_table", null, "update", Map.of());
        return service.cancel(jobId);
    }

    @GetMapping("/maintenance-jobs/{jobId}/plan")
    public com.aetheris.metadata.service.LakehouseMaintenancePlan.Plan plan(
            HttpServletRequest request, @PathVariable Long jobId) {
        security.require(request, "lakehouse_table", null, "view", Map.of());
        return service.preview(jobId);
    }
}
