package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.MetadataSyncRequest;
import com.aetheris.metadata.dto.WarehouseMap;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.MetadataSyncService;
import com.aetheris.metadata.service.MetadataTableService;
import com.aetheris.metadata.service.WarehouseMapService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/tables")
public class MetadataTableController {

    private static final Logger log = LoggerFactory.getLogger(MetadataTableController.class);

    private final MetadataTableService service;
    private final DataSourceService dataSourceService;
    private final MetadataSyncService syncService;
    private final WarehouseMapService warehouseMapService;

    public MetadataTableController(
            MetadataTableService service,
            DataSourceService dataSourceService,
            MetadataSyncService syncService,
            WarehouseMapService warehouseMapService) {
        this.service = service;
        this.dataSourceService = dataSourceService;
        this.syncService = syncService;
        this.warehouseMapService = warehouseMapService;
    }

    @GetMapping
    public List<MetadataTable> list() { return service.list(); }

    /**
     * 数仓地图：按层级聚合的表分布。
     *
     * @param datasourceName 只统计指定数据源；为空时统计全部
     * @param includeStale   是否纳入已消失（stale）的表，默认不含
     */
    @GetMapping("/warehouse-map")
    public WarehouseMap warehouseMap(
            @RequestParam(required = false) String datasourceName,
            @RequestParam(defaultValue = "false") boolean includeStale) {
        return warehouseMapService.build(datasourceName, includeStale);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MetadataTable> getById(@PathVariable Long id) {
        MetadataTable table = service.getById(id);
        return table != null ? ResponseEntity.ok(table) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public MetadataTable create(@Valid @RequestBody MetadataTable table) {
        table.setSource("manual");
        service.save(table);
        return table;
    }

    @PutMapping("/{id}")
    public ResponseEntity<MetadataTable> update(@PathVariable Long id, @Valid @RequestBody MetadataTable table) {
        table.setId(id);
        boolean updated = service.updateById(table);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = service.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** 从指定数据源采集库表元数据；schema 为空时采集全部非系统库。 */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(@Valid @RequestBody MetadataSyncRequest request) {
        DataSource dataSource = dataSourceService.getById(request.datasourceId());
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(syncService.sync(dataSource, request.schemaName()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (Exception error) {
            log.warn("Metadata sync failed for datasource {}", request.datasourceId(), error);
            return ResponseEntity.status(502).body(Map.of("message", "元数据采集失败，请检查数据源连通性"));
        }
    }
}
