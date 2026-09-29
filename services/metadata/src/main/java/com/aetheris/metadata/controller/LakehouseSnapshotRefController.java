package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.LakehouseSnapshotRef;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.LakehouseSnapshotRefService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/lakehouse")
public class LakehouseSnapshotRefController {
    private final LakehouseSnapshotRefService service;
    private final MetadataSecurity security;

    public LakehouseSnapshotRefController(LakehouseSnapshotRefService service, MetadataSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping("/tables/{tableId}/refs")
    public List<LakehouseSnapshotRef> list(HttpServletRequest request, @PathVariable Long tableId) {
        security.require(request, "lakehouse_table", tableId, "view", Map.of());
        return service.listForTable(tableId);
    }

    @PostMapping("/tables/{tableId}/refs")
    public ResponseEntity<LakehouseSnapshotRef> create(HttpServletRequest request, @PathVariable Long tableId,
                                                       @Valid @RequestBody LakehouseSnapshotRef ref) {
        var identity = security.require(request, "lakehouse_table", tableId, "update", Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(tableId, ref, identity.username()));
    }

    @PostMapping("/refs/{refId}/move")
    public LakehouseSnapshotRef move(HttpServletRequest request, @PathVariable Long refId,
                                     @RequestBody Map<String, String> body) {
        security.require(request, "lakehouse_table", null, "update", Map.of());
        return service.move(refId, body == null ? null : body.get("snapshotId"), security.requireIdentity(request).username());
    }

    @DeleteMapping("/refs/{refId}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long refId) {
        security.require(request, "lakehouse_table", null, "update", Map.of());
        service.removeRef(refId);
        return ResponseEntity.noContent().build();
    }
}
