package com.aetheris.system.controller;

import com.aetheris.system.entity.SystemConfig;
import com.aetheris.system.service.SystemConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    public List<SystemConfig> list() {
        return systemConfigService.list();
    }

    @GetMapping("/{key}")
    public ResponseEntity<SystemConfig> getByKey(@PathVariable String key) {
        return systemConfigService.findByKey(key)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
