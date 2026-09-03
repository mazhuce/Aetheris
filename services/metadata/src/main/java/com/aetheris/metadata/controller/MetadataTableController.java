package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.MetadataSyncRequest;
import com.aetheris.metadata.dto.WarehouseMap;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.BusinessDomainService;
import com.aetheris.metadata.service.MetadataSyncService;
import com.aetheris.metadata.service.MetadataTableService;
import com.aetheris.metadata.service.WarehouseMapService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
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
    private final BusinessDomainService businessDomainService;
    private final MetadataSecurity security;

    public MetadataTableController(
            MetadataTableService service,
            DataSourceService dataSourceService,
            MetadataSyncService syncService,
            WarehouseMapService warehouseMapService,
            BusinessDomainService businessDomainService,
            MetadataSecurity security) {
        this.service = service;
        this.dataSourceService = dataSourceService;
        this.syncService = syncService;
        this.warehouseMapService = warehouseMapService;
        this.businessDomainService = businessDomainService;
        this.security = security;
    }

    @GetMapping
    public List<MetadataTable> list(HttpServletRequest request) {
        security.requireIdentity(request);
        MetadataSecurity.Scope scope = security.visibility(request, "table", "view");
        return switch (scope.mode()) {
            case ALL -> service.list();
            case IDS -> scope.ids().isEmpty() ? List.of()
                    : tableList(new LambdaQueryWrapper<MetadataTable>()
                            .in(MetadataTable::getBusinessDomainId, scope.ids()));
            case OPAQUE -> filteredList(request, service.list());
        };
    }

    /**
     * 数仓地图：按层级聚合的表分布。
     *
     * @param datasourceName 只统计指定数据源；为空时统计全部
     * @param includeStale   是否纳入已消失（stale）的表，默认不含
     */
    @GetMapping("/warehouse-map")
    public WarehouseMap warehouseMap(HttpServletRequest request,
            @RequestParam(required = false) String datasourceName,
            @RequestParam(defaultValue = "false") boolean includeStale) {
        security.require(request, "table", null, "view", Map.of());
        return warehouseMapService.build(datasourceName, includeStale);
    }

    /**
     * 按关键词检索表，供控制台顶部的全局搜索使用。
     *
     * <p>映射必须声明在 {@code /{id}} 之前，否则 "search" 会被当作 id 解析而返回 400。
     * 结果会按当前用户的表可见范围过滤，避免搜索框枚举无权限的表。
     *
     * @param keyword 关键词；为空时返回空列表而非全表，避免搜索框空输入时拉回全部数据
     * @param limit   返回条数上限，防止一次拉回过多结果拖慢下拉面板
     */
    @GetMapping("/search")
    public List<MetadataTable> search(HttpServletRequest request,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        security.requireIdentity(request);
        List<MetadataTable> candidates = service.search(keyword, limit);
        MetadataSecurity.Scope scope = security.visibility(request, "table", "view");
        return switch (scope.mode()) {
            case ALL -> candidates;
            case IDS -> candidates.stream()
                    .filter(table -> table.getBusinessDomainId() != null
                            && scope.ids().contains(table.getBusinessDomainId()))
                    .toList();
            case OPAQUE -> filteredList(request, candidates);
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<MetadataTable> getById(HttpServletRequest request, @PathVariable Long id) {
        MetadataTable table = service.getById(id);
        if (table == null) {
            return ResponseEntity.notFound().build();
        }
        security.require(request, "table", id, "view", domainCtx(table.getBusinessDomainId()));
        return ResponseEntity.ok(table);
    }

    @PostMapping
    public MetadataTable create(HttpServletRequest request, @Valid @RequestBody MetadataTable table) {
        security.require(request, "table", null, "create", domainCtx(table.getBusinessDomainId()));
        table.setSource("manual");
        applyBusinessDomain(table);
        service.save(table);
        return table;
    }

    @PutMapping("/{id}")
    public ResponseEntity<MetadataTable> update(HttpServletRequest request, @PathVariable Long id,
                                                @Valid @RequestBody MetadataTable table) {
        MetadataTable existing = service.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        security.require(request, "table", id, "update", domainCtx(existing.getBusinessDomainId()));
        table.setId(id);
        applyBusinessDomain(table);
        if (table.getLayer() != null) table.setLayerSource("manual");
        boolean updated = service.updateById(table);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    private void applyBusinessDomain(MetadataTable table) {
        if (table.getBusinessDomainId() == null) return;
        var domain = businessDomainService.require(table.getBusinessDomainId());
        if (!"active".equals(domain.getStatus())) throw new IllegalArgumentException("不能关联已停用的业务域");
        table.setDomain(domain.getDomainName());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        MetadataTable existing = service.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        security.require(request, "table", id, "delete", domainCtx(existing.getBusinessDomainId()));
        boolean removed = service.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** 从指定数据源采集库表元数据；schema 为空时采集全部非系统库。 */
    @PostMapping("/sync")
    public ResponseEntity<?> sync(HttpServletRequest request, @Valid @RequestBody MetadataSyncRequest syncRequest) {
        // 元数据采集同时读数据源并写表元数据：要求数据源查询与表更新两级能力，
        // admin 全量通过；viewer 默认无写权限被拒绝。
        security.require(request, "table", null, "update", Map.of());
        DataSource dataSource = dataSourceService.getById(syncRequest.datasourceId());
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(syncService.sync(dataSource, syncRequest.schemaName()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (Exception error) {
            log.warn("Metadata sync failed for datasource {}", syncRequest.datasourceId(), error);
            return ResponseEntity.status(502).body(Map.of("message", "元数据采集失败，请检查数据源连通性"));
        }
    }

    private List<MetadataTable> tableList(LambdaQueryWrapper<MetadataTable> wrapper) {
        return service.list(wrapper);
    }

    private List<MetadataTable> filteredList(HttpServletRequest request, List<MetadataTable> tables) {
        String username = security.requireIdentity(request).username();
        return tables.stream()
                .filter(table -> security.isAllowed(username, "table", table.getId(), "view",
                        domainCtx(table.getBusinessDomainId())))
                .toList();
    }

    private static Map<String, String> domainCtx(Long domainId) {
        return domainId == null ? Map.of() : Map.of("domainId", String.valueOf(domainId));
    }
}