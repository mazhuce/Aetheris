package com.aetheris.system.controller;

import com.aetheris.system.entity.SystemConfig;
import com.aetheris.system.dto.SystemConfigResponse;
import com.aetheris.system.service.SystemConfigService;
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
@RequestMapping("/api/system/configs")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    public SystemConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    @GetMapping
    public List<SystemConfigResponse> list() {
        return systemConfigService.list().stream().map(SystemConfigResponse::from).toList();
    }

    @GetMapping("/{key}")
    public ResponseEntity<SystemConfigResponse> getByKey(@PathVariable String key) {
        return systemConfigService.findByKey(key)
                .map(SystemConfigResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public SystemConfigResponse create(@Valid @RequestBody SystemConfig config) {
        systemConfigService.save(config);
        return SystemConfigResponse.from(config);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SystemConfigResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody SystemConfig config) {
        config.setId(id);
        boolean updated = systemConfigService.updateById(config);
        return updated ? ResponseEntity.ok(SystemConfigResponse.from(systemConfigService.getById(id))) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        boolean removed = systemConfigService.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
