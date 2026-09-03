package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.LineageParseRequest;
import com.aetheris.metadata.dto.LineageParseResult;
import com.aetheris.metadata.entity.LineageRelation;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.LineageParseService;
import com.aetheris.metadata.service.LineageRelationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 表级血缘端点。读取要求认证身份；登记、解析与维护视为表元数据的写管理，
 * 需要 table.update 权限（admin 经决策引擎全量通过，viewer 默认拒绝）。
 */
@RestController
@RequestMapping("/api/metadata/lineages")
public class LineageRelationController {

    private final LineageRelationService service;
    private final LineageParseService parseService;
    private final MetadataSecurity security;

    public LineageRelationController(
            LineageRelationService service,
            LineageParseService parseService,
            MetadataSecurity security) {
        this.service = service;
        this.parseService = parseService;
        this.security = security;
    }

    /** 从 SQL 解析表级血缘并入库；同一目标表重复解析时替换其既有解析边。 */
    @PostMapping("/parse")
    public LineageParseResult parse(HttpServletRequest request, @Valid @RequestBody LineageParseRequest parseRequest) {
        security.require(request, "table", null, "update", Map.of());
        return parseService.parseAndSave(parseRequest.sql(), parseRequest.relationType());
    }

    @GetMapping
    public List<LineageRelation> list(HttpServletRequest request) {
        security.requireIdentity(request);
        return service.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<LineageRelation> getById(HttpServletRequest request, @PathVariable Long id) {
        security.requireIdentity(request);
        LineageRelation relation = service.getById(id);
        return relation != null ? ResponseEntity.ok(relation) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public LineageRelation create(HttpServletRequest request, @Valid @RequestBody LineageRelation relation) {
        security.require(request, "table", null, "update", Map.of());
        // 标记为人工登记，避免后续 SQL 解析把它当作过期的解析结果删除。
        relation.setSource("manual");
        service.save(relation);
        return relation;
    }

    @PutMapping("/{id}")
    public ResponseEntity<LineageRelation> update(HttpServletRequest request, @PathVariable Long id,
                                                  @Valid @RequestBody LineageRelation relation) {
        security.require(request, "table", null, "update", Map.of());
        relation.setId(id);
        boolean updated = service.updateById(relation);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "table", null, "update", Map.of());
        boolean removed = service.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}