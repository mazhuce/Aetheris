package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.LakehouseTable;
import com.aetheris.metadata.entity.LakehouseTableSnapshot;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.LakehouseTableService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** R5 控制面：登记和对账 Iceberg 表，不直接伪造远端 Iceberg 操作结果。 */
@RestController
@RequestMapping("/api/metadata/lakehouse/tables")
public class LakehouseTableController {
    private final LakehouseTableService service;
    private final MetadataSecurity security;

    public LakehouseTableController(LakehouseTableService service, MetadataSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping
    public List<LakehouseTable> list(HttpServletRequest request) {
        security.require(request, "lakehouse_table", null, "view", Map.of());
        return service.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<LakehouseTable> get(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "lakehouse_table", id, "view", Map.of());
        LakehouseTable table = service.getById(id);
        return table == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(table);
    }

    @PostMapping
    public ResponseEntity<LakehouseTable> register(HttpServletRequest request, @Valid @RequestBody LakehouseTable table) {
        var identity = security.require(request, "lakehouse_table", null, "create", Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(table, identity.username()));
    }

    @PostMapping("/{id}/snapshots")
    public ResponseEntity<LakehouseTableSnapshot> recordSnapshot(HttpServletRequest request, @PathVariable Long id,
                                                                  @Valid @RequestBody LakehouseTableSnapshot snapshot) {
        var identity = security.require(request, "lakehouse_table", id, "update", Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordSnapshot(id, snapshot, identity.username()));
    }

    @GetMapping("/{id}/snapshots")
    public List<LakehouseTableSnapshot> snapshots(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "lakehouse_table", id, "view", Map.of());
        return service.snapshots(id);
    }

    /** 仅更新控制面当前快照指针；真实 Iceberg rollback 由受管维护任务执行并回写证据。 */
    @PostMapping("/{id}/current-snapshot")
    public LakehouseTable setCurrentSnapshot(HttpServletRequest request, @PathVariable Long id,
                                              @RequestBody Map<String, String> body) {
        var identity = security.require(request, "lakehouse_table", id, "update", Map.of());
        return service.setCurrentSnapshot(id, body == null ? null : body.get("snapshotId"), identity.username());
    }
}
