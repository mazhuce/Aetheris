package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.LakehouseSchemaChange;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.LakehouseSchemaChangeService;
import com.aetheris.metadata.service.LakehouseTableService;
import com.aetheris.metadata.service.SchemaEvolutionSqlPlanner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/lakehouse")
public class LakehouseSchemaChangeController {
    private final LakehouseSchemaChangeService service;
    private final LakehouseTableService tableService;
    private final MetadataSecurity security;

    public LakehouseSchemaChangeController(LakehouseSchemaChangeService service, LakehouseTableService tableService,
                                           MetadataSecurity security) {
        this.service = service;
        this.tableService = tableService;
        this.security = security;
    }

    @GetMapping("/tables/{tableId}/schema-changes")
    public List<LakehouseSchemaChange> list(HttpServletRequest request, @PathVariable Long tableId) {
        security.require(request, "lakehouse_table", tableId, "view", Map.of());
        return service.listForTable(tableId);
    }

    @PostMapping("/tables/{tableId}/schema-changes")
    public ResponseEntity<LakehouseSchemaChange> propose(HttpServletRequest request, @PathVariable Long tableId,
                                                         @Valid @RequestBody LakehouseSchemaChange change) {
        var identity = security.require(request, "lakehouse_table", tableId, "update", Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.propose(tableId, change, identity.username()));
    }

    @PostMapping("/schema-changes/{changeId}/review")
    public LakehouseSchemaChange review(HttpServletRequest request, @PathVariable Long changeId,
                                        @RequestParam Long tableId, @RequestBody Map<String, Boolean> body) {
        security.require(request, "lakehouse_table", tableId, "update", Map.of());
        assertBelongsToTable(changeId, tableId);
        return service.review(changeId, Boolean.TRUE.equals(body == null ? null : body.get("approved")),
                security.requireIdentity(request).username());
    }

    @PostMapping("/schema-changes/{changeId}/isolate")
    public LakehouseSchemaChange isolate(HttpServletRequest request, @PathVariable Long changeId,
                                         @RequestParam Long tableId) {
        security.require(request, "lakehouse_table", tableId, "update", Map.of());
        assertBelongsToTable(changeId, tableId);
        return service.isolate(changeId, security.requireIdentity(request).username());
    }

    @GetMapping("/schema-changes/{changeId}/plan")
    public SchemaEvolutionSqlPlanner.Plan plan(HttpServletRequest request, @PathVariable Long changeId,
                                               @RequestParam Long tableId) {
        security.require(request, "lakehouse_table", tableId, "view", Map.of());
        LakehouseSchemaChange change = service.getById(changeId);
        if (change == null || !tableId.equals(change.getLakehouseTableId())) {
            throw new IllegalArgumentException("Schema 变更不存在或不属于指定湖仓表");
        }
        return SchemaEvolutionSqlPlanner.plan(tableService.getById(tableId), change);
    }

    @PostMapping("/schema-changes/{changeId}/execute")
    public ResponseEntity<LakehouseSchemaChange> execute(HttpServletRequest request, @PathVariable Long changeId,
                                                         @RequestParam Long tableId) {
        security.require(request, "lakehouse_table", tableId, "update", Map.of());
        assertBelongsToTable(changeId, tableId);
        return ResponseEntity.accepted().body(service.execute(changeId));
    }

    @PostMapping("/schema-changes/{changeId}/reconcile")
    public LakehouseSchemaChange reconcile(HttpServletRequest request, @PathVariable Long changeId,
                                           @RequestParam Long tableId) {
        security.require(request, "lakehouse_table", tableId, "update", Map.of());
        assertBelongsToTable(changeId, tableId);
        return service.reconcile(changeId);
    }

    @PostMapping("/schema-changes/{changeId}/cancel")
    public LakehouseSchemaChange cancel(HttpServletRequest request, @PathVariable Long changeId,
                                       @RequestParam Long tableId) {
        security.require(request, "lakehouse_table", tableId, "update", Map.of());
        assertBelongsToTable(changeId, tableId);
        return service.cancel(changeId);
    }

    private void assertBelongsToTable(Long changeId, Long tableId) {
        LakehouseSchemaChange change = service.getById(changeId);
        if (change == null || !tableId.equals(change.getLakehouseTableId())) {
            throw new IllegalArgumentException("Schema 变更不存在或不属于指定湖仓表");
        }
    }
}
