package com.aetheris.workflow.controller;

import com.aetheris.workflow.security.GatewayIdentityInterceptor;
import com.aetheris.workflow.service.ExecutionAdapter;
import com.aetheris.workflow.service.FlinkSqlExecutionClient;
import com.aetheris.workflow.service.GitRepositoryService;
import com.aetheris.workflow.service.GitRepositoryService.RepositoryInput;
import com.aetheris.workflow.service.GitRepositoryService.RepositoryStatusInput;
import com.aetheris.workflow.service.GitRepositoryService.RepositoryTrustInput;
import com.aetheris.workflow.service.ManagedSparkCatalogService;
import com.aetheris.workflow.service.ManagedSparkCatalogService.ArtifactInput;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobInput;
import com.aetheris.workflow.service.SparkResourceQueueService;
import com.aetheris.workflow.service.SparkResourceQueueService.RunnerInput;
import com.aetheris.workflow.service.SparkResourceQueueService.RunnerStatusInput;
import com.aetheris.workflow.service.WorkflowAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;

/** Managed Spark environment, artifact and versioned job-definition endpoints. */
@RestController
@RequestMapping("/api/workflow/spark")
public class ManagedSparkController {

    private final ManagedSparkCatalogService catalog;
    private final ExecutionAdapter adapter;
    private final SparkResourceQueueService resourceQueue;
    private final GitRepositoryService gitRepositories;
    private final WorkflowAuthorizationService authorization;
    private final FlinkSqlExecutionClient flinkGateway;
    @Value("${aetheris.flink-sql-gateway.base-url:http://127.0.0.1:18083}")
    private String flinkGatewayEndpoint;

    public ManagedSparkController(ManagedSparkCatalogService catalog,
                                  com.aetheris.workflow.service.SparkBatchExecutionAdapter adapter,
                                  SparkResourceQueueService resourceQueue,
                                  GitRepositoryService gitRepositories,
                                  WorkflowAuthorizationService authorization) {
        this.catalog = catalog;
        this.adapter = adapter;
        this.resourceQueue = resourceQueue;
        this.gitRepositories = gitRepositories;
        this.authorization = authorization;
        this.flinkGateway = null;
    }

    @Autowired
    public ManagedSparkController(ManagedSparkCatalogService catalog,
                                  com.aetheris.workflow.service.SparkBatchExecutionAdapter adapter,
                                  SparkResourceQueueService resourceQueue,
                                  GitRepositoryService gitRepositories,
                                  WorkflowAuthorizationService authorization,
                                  FlinkSqlExecutionClient flinkGateway) {
        this.catalog = catalog;
        this.adapter = adapter;
        this.resourceQueue = resourceQueue;
        this.gitRepositories = gitRepositories;
        this.authorization = authorization;
        this.flinkGateway = flinkGateway;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        try {
            return ResponseEntity.ok(adapter.health());
        } catch (RuntimeException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "message", error.getMessage()));
        }
    }

    @GetMapping("/flink/health")
    public ResponseEntity<?> flinkHealth(@RequestParam(required = false) String endpoint) {
        if (flinkGateway == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "message", "Flink SQL Gateway 客户端未配置"));
        }
        final String target;
        try {
            target = normalizeFlinkEndpoint(endpoint);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID", "message", error.getMessage()));
        }
        try {
            return ResponseEntity.ok(flinkGateway.health(target));
        } catch (RuntimeException error) {
            String message = error.getMessage();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "endpoint", target,
                            "message", message == null || message.isBlank() ? "Flink SQL Gateway 不可达" : message));
        }
    }

    private String normalizeFlinkEndpoint(String requested) {
        String value = StringUtils.hasText(requested) ? requested.trim() : flinkGatewayEndpoint;
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("Flink SQL Gateway 地址必须是无凭据、无查询参数的 HTTP(S) 地址");
            }
            return value.replaceAll("/+$", "");
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Flink SQL Gateway 地址必须是有效的 HTTP(S) 地址");
        }
    }

    @GetMapping("/environments")
    public Object environments() { return catalog.environments(); }

    @GetMapping("/runners")
    public ResponseEntity<?> runners(
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权查看 Runner 资源容量"));
        }
        return ResponseEntity.ok(resourceQueue.runners());
    }

    @PostMapping("/runners")
    public ResponseEntity<?> registerRunner(
            @RequestBody RunnerInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权注册 Runner"));
        }
        return action(() -> resourceQueue.register(input, username));
    }

    @PostMapping("/runners/{id}/status")
    public ResponseEntity<?> changeRunnerStatus(
            @PathVariable long id, @RequestBody RunnerStatusInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权修改 Runner 状态"));
        }
        return action(() -> resourceQueue.changeStatus(id, input.status()));
    }

    @GetMapping("/repositories")
    public ResponseEntity<?> repositories(
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权查看 Git 仓库"));
        }
        return ResponseEntity.ok(gitRepositories.repositories());
    }

    @PostMapping("/repositories")
    public ResponseEntity<?> registerRepository(
            @RequestBody RepositoryInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权注册 Git 仓库"));
        }
        return action(() -> gitRepositories.register(input, username));
    }

    @PostMapping("/repositories/{id}/sync")
    public ResponseEntity<?> synchronizeRepository(
            @PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权同步 Git 仓库"));
        }
        return action(() -> gitRepositories.synchronize(id, username));
    }

    @PostMapping("/repositories/{id}/status")
    public ResponseEntity<?> changeRepositoryStatus(
            @PathVariable long id, @RequestBody RepositoryStatusInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权修改 Git 仓库状态"));
        }
        return action(() -> gitRepositories.changeStatus(id, input.status(), username));
    }

    @GetMapping("/repositories/{id}/revisions")
    public ResponseEntity<?> revisions(
            @PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权查看 Git 修订历史"));
        }
        return action(() -> gitRepositories.revisions(id));
    }

    @PostMapping("/repositories/{id}/trust")
    public ResponseEntity<?> changeRepositoryTrust(
            @PathVariable long id, @RequestBody RepositoryTrustInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权修改 Git 仓库信任策略"));
        }
        return action(() -> gitRepositories.changeTrust(id, input, username));
    }

    @GetMapping("/artifacts")
    public Object artifacts() { return catalog.artifacts(); }

    @PostMapping("/artifacts")
    public ResponseEntity<?> register(
            @RequestBody ArtifactInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.registerArtifact(input, username));
    }

    @PostMapping(value = "/artifacts/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam String name,
            @RequestParam String version,
            @RequestParam String artifactKey,
            @RequestParam MultipartFile file,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> {
            try {
                return catalog.registerUploadedArtifact(name, version, artifactKey, file.getBytes(), username);
            } catch (java.io.IOException error) {
                throw new IllegalArgumentException("无法读取上传的 Spark 制品");
            }
        });
    }

    @PostMapping("/artifacts/{id}/approve")
    public ResponseEntity<?> approve(
            @PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.approveArtifact(id, username));
    }

    @PostMapping("/artifacts/{id}/verify")
    public ResponseEntity<?> verifyArtifact(
            @PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权重新验证 Git 制品"));
        }
        return action(() -> catalog.verifyArtifact(id, username));
    }

    @GetMapping("/artifacts/{id}/verifications")
    public ResponseEntity<?> artifactVerifications(
            @PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        if (!authorization.mayManage(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "无权查看制品验证证据"));
        }
        return action(() -> catalog.verifications(id));
    }

    @PostMapping("/artifacts/{id}/revoke")
    public ResponseEntity<?> revoke(@PathVariable long id) {
        return action(() -> catalog.revokeArtifact(id));
    }

    @GetMapping("/jobs")
    public Object jobs() { return catalog.jobs(); }

    @PostMapping("/jobs")
    public ResponseEntity<?> createJob(
            @RequestBody SparkJobInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.createJob(input, username));
    }

    @PostMapping("/jobs/{id}/publish")
    public ResponseEntity<?> publishJob(
            @PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.publishJob(id, username));
    }

    @PostMapping("/jobs/{id}/disable")
    public ResponseEntity<?> disableJob(@PathVariable long id) {
        return action(() -> catalog.disableJob(id));
    }

    private ResponseEntity<?> action(Supplier<?> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage()));
        }
    }
}
