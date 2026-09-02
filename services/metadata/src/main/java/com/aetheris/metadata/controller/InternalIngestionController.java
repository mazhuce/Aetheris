package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.IngestionExecuteRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 内部批量接入端点；数据源凭据始终留在 metadata 服务。 */
@RestController
@RequestMapping("/internal/ingestion")
public class InternalIngestionController {

    private static final Logger log = LoggerFactory.getLogger(InternalIngestionController.class);

    private final DataSourceService dataSourceService;
    private final JdbcService jdbcService;

    public InternalIngestionController(DataSourceService dataSourceService, JdbcService jdbcService) {
        this.dataSourceService = dataSourceService;
        this.jdbcService = jdbcService;
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(@Valid @RequestBody IngestionExecuteRequest request) {
        DataSource source = dataSourceService.getById(request.sourceDatasourceId());
        DataSource target = dataSourceService.getById(request.targetDatasourceId());
        if (source == null || target == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(jdbcService.transfer(
                    source,
                    target,
                    request.sourceSql(),
                    request.targetTable(),
                    request.writeMode(),
                    request.batchSize()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", error.getMessage()));
        } catch (IllegalStateException error) {
            log.warn("Ingestion transfer failed: source={}, target={}", source.getId(), target.getId(), error);
            return ResponseEntity.status(502).body(Map.of("success", false, "message", error.getMessage()));
        }
    }
}
