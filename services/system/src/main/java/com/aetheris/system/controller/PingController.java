package com.aetheris.system.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class PingController {

    @Value("${spring.application.name}")
    private String serviceName;

    @Value("${info.app.version:0.1.0}")
    private String version;

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "service", serviceName,
                "version", version,
                "status", "UP",
                "timestamp", Instant.now().toString()
        );
    }
}
