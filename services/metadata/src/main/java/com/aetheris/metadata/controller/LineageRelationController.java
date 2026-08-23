package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.LineageRelation;
import com.aetheris.metadata.service.LineageRelationService;
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

@RestController
@RequestMapping("/api/metadata/lineages")
public class LineageRelationController {

    private final LineageRelationService service;

    public LineageRelationController(LineageRelationService service) {
        this.service = service;
    }

    @GetMapping
    public List<LineageRelation> list() { return service.list(); }

    @GetMapping("/{id}")
    public ResponseEntity<LineageRelation> getById(@PathVariable Long id) {
        LineageRelation relation = service.getById(id);
        return relation != null ? ResponseEntity.ok(relation) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public LineageRelation create(@Valid @RequestBody LineageRelation relation) {
        service.save(relation);
        return relation;
    }

    @PutMapping("/{id}")
    public ResponseEntity<LineageRelation> update(@PathVariable Long id, @Valid @RequestBody LineageRelation relation) {
        relation.setId(id);
        boolean updated = service.updateById(relation);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = service.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
