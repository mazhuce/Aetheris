package com.aetheris.workflow.controller;

import com.aetheris.workflow.security.GatewayIdentityInterceptor;
import com.aetheris.workflow.service.ExecutionAdapter;
import com.aetheris.workflow.service.GitRepositoryService;
import com.aetheris.workflow.service.GitRepositoryService.RepositoryInput;
import com.aetheris.workflow.service.GitRepositoryService.RepositoryStatusInput;
import com.aetheris.workflow.service.ManagedSparkCatalogService;
import com.aetheris.workflow.service.ManagedSparkCatalogService.ArtifactInput;
import com.aetheris.workflow.service.ManagedSparkCatalogService.SparkJobInput;
import com.aetheris.workflow.service.SparkResourceQueueService;
import com.aetheris.workflow.service.SparkResourceQueueService.RunnerInput;
import com.aetheris.workflow.service.SparkResourceQueueService.RunnerStatusInput;
import com.aetheris.workflow.service.WorkflowAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/artifacts")
    public Object artifacts() { return catalog.artifacts(); }

    @PostMapping("/artifacts")
    public ResponseEntity<?> register(
            @RequestBody ArtifactInput input,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.registerArtifact(input, username));
    }

    @PostMapping("/artifacts/{id}/approve")
    public ResponseEntity<?> approve(
            @PathVariable long id,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        return action(() -> catalog.approveArtifact(id, username));
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
