package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.service.IngestionTaskService;
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

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/ingestion/tasks")
public class IngestionTaskController {

    private final IngestionTaskService service;

    public IngestionTaskController(IngestionTaskService service) {
        this.service = service;
    }

    @GetMapping
    public List<IngestionTask> list() { return service.list(); }

    @GetMapping("/{id}")
    public ResponseEntity<IngestionTask> getById(@PathVariable Long id) {
        IngestionTask task = service.getById(id);
        return task != null ? ResponseEntity.ok(task) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public IngestionTask create(@Valid @RequestBody IngestionTask task) {
        service.save(task);
        return task;
    }

    @PutMapping("/{id}")
    public ResponseEntity<IngestionTask> update(@PathVariable Long id, @Valid @RequestBody IngestionTask task) {
        task.setId(id);
        boolean updated = service.updateById(task);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = service.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<IngestionTask> run(@PathVariable Long id) {
        IngestionTask task = service.getById(id);
        if (task == null) return ResponseEntity.notFound().build();
        task.setLastRunStatus("success");
        task.setLastRunAt(OffsetDateTime.now());
        service.updateById(task);
        return ResponseEntity.ok(service.getById(id));
    }
}
