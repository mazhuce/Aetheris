package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.DataSourceService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/datasources")
public class DataSourceController {

    private static final Logger log = LoggerFactory.getLogger(DataSourceController.class);

    private final DataSourceService dataSourceService;
    private final JdbcService jdbcService;

    public DataSourceController(DataSourceService dataSourceService, JdbcService jdbcService) {
        this.dataSourceService = dataSourceService;
        this.jdbcService = jdbcService;
    }

    @GetMapping
    public List<DataSource> list() {
        return dataSourceService.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataSource> getById(@PathVariable Long id) {
        DataSource dataSource = dataSourceService.getById(id);
        return dataSource != null
                ? ResponseEntity.ok(dataSource)
                : ResponseEntity.notFound().build();
    }

    @PostMapping
    public DataSource create(@Valid @RequestBody DataSource dataSource) {
        dataSourceService.save(dataSource);
        return dataSource;
    }

    @PutMapping("/{id}")
    public ResponseEntity<DataSource> update(
            @PathVariable Long id,
            @Valid @RequestBody DataSource dataSource) {
        dataSource.setId(id);
        boolean updated = dataSourceService.updateById(dataSource);
        return updated
                ? ResponseEntity.ok(dataSourceService.getById(id))
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = dataSourceService.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id) {
        DataSource dataSource = dataSourceService.getById(id);
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(jdbcService.test(dataSource));
    }

    @PostMapping("/{id}/query")
    public ResponseEntity<Map<String, Object>> query(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        DataSource dataSource = dataSourceService.getById(id);
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(jdbcService.query(dataSource, body.get("sql")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("Datasource query failed", e);
            return ResponseEntity.status(500).body(Map.of("message", "查询失败，请检查 SQL 与数据源配置"));
        }
    }
}
