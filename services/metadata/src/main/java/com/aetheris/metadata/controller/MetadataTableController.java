package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.service.MetadataTableService;
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
@RequestMapping("/api/metadata/tables")
public class MetadataTableController {

    private final MetadataTableService service;

    public MetadataTableController(MetadataTableService service) {
        this.service = service;
    }

    @GetMapping
    public List<MetadataTable> list() { return service.list(); }

    @GetMapping("/{id}")
    public ResponseEntity<MetadataTable> getById(@PathVariable Long id) {
        MetadataTable table = service.getById(id);
        return table != null ? ResponseEntity.ok(table) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public MetadataTable create(@Valid @RequestBody MetadataTable table) {
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
}
