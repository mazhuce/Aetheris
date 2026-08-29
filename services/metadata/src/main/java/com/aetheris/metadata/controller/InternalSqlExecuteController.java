package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.SqlExecuteRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部 SQL 执行端点，供 workflow 服务执行数仓加工任务。
 *
 * <p>为什么执行能力放在 metadata 而不是 workflow 自己连库：metadata 已经持有数据源凭据、
 * JDBC 驱动、URL 协议校验与危险连接参数黑名单。把这些复制到 workflow 不只是重复代码，
 * 而是复制一份安全边界——两边任一处遗漏就是缺口。workflow 只负责调度与状态。
 *
 * <p>安全前提：路径以 {@code /internal} 开头，网关只路由 {@code /api/**}，
 * 因此该端点不对外暴露，仅在 Compose 内部网络可达。写入语句仍经 SqlWriteGuard 白名单校验，
 * 即便调用方被攻破也无法执行 DROP / GRANT 等破坏性操作。
 */
@RestController
@RequestMapping("/internal/datasources")
public class InternalSqlExecuteController {

    private static final Logger log = LoggerFactory.getLogger(InternalSqlExecuteController.class);

    private final DataSourceService dataSourceService;
    private final JdbcService jdbcService;

    public InternalSqlExecuteController(DataSourceService dataSourceService, JdbcService jdbcService) {
        this.dataSourceService = dataSourceService;
        this.jdbcService = jdbcService;
    }

    /** 在指定数据源上执行一条写入语句。 */
    @PostMapping("/{id}/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @PathVariable Long id,
            @Valid @RequestBody SqlExecuteRequest request) {
        DataSource dataSource = dataSourceService.getById(id);
        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            return ResponseEntity.ok(
                    jdbcService.execute(dataSource, request.sql(), request.timeoutSeconds()));
        } catch (IllegalArgumentException error) {
            // 语句未通过白名单校验：这是调用方的问题，原因可安全回显以便定位。
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", error.getMessage()));
        } catch (IllegalStateException error) {
            // 执行期失败（连接、语法、权限）。JdbcService 已把驱动原文换成安全文案。
            log.warn("Internal SQL execution failed on datasource {}", id, error);
            return ResponseEntity.status(502)
                    .body(Map.of("success", false, "message", error.getMessage()));
        }
    }
}
