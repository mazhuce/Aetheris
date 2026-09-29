package com.aetheris.workflow.controller;

import com.aetheris.workflow.service.ManagedSparkCatalogService;
import com.aetheris.workflow.service.GitRepositoryService;
import com.aetheris.workflow.service.SparkBatchExecutionAdapter;
import com.aetheris.workflow.service.FlinkSqlExecutionClient;
import com.aetheris.workflow.service.SparkResourceQueueService;
import com.aetheris.workflow.service.WorkflowAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedSparkControllerTest {

    @Test
    void runnerCapacityIsRestrictedToManagers() {
        SparkResourceQueueService queue = mock(SparkResourceQueueService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.mayManage("alice")).thenReturn(false);
        ManagedSparkController controller = new ManagedSparkController(
                mock(ManagedSparkCatalogService.class), mock(SparkBatchExecutionAdapter.class),
                queue, mock(GitRepositoryService.class), authorization);

        ResponseEntity<?> response = controller.runners("alice");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(queue, never()).runners();
    }

    @Test
    void runnerRegistrationHasDefenseInDepthAuthorization() {
        SparkResourceQueueService queue = mock(SparkResourceQueueService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.mayManage("alice")).thenReturn(false);
        ManagedSparkController controller = new ManagedSparkController(
                mock(ManagedSparkCatalogService.class), mock(SparkBatchExecutionAdapter.class),
                queue, mock(GitRepositoryService.class), authorization);
        SparkResourceQueueService.RunnerInput input = new SparkResourceQueueService.RunnerInput(
                1L, "runner-a", "Runner A", "http://runner-a:18081", 4, 4096, 2);

        ResponseEntity<?> response = controller.registerRunner(input, "alice");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(queue, never()).register(input, "alice");
    }

    @Test
    void repositoryRegistryHasDefenseInDepthAuthorization() {
        GitRepositoryService repositories = mock(GitRepositoryService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.mayManage("alice")).thenReturn(false);
        ManagedSparkController controller = new ManagedSparkController(
                mock(ManagedSparkCatalogService.class), mock(SparkBatchExecutionAdapter.class),
                mock(SparkResourceQueueService.class), repositories, authorization);
        GitRepositoryService.RepositoryInput input = new GitRepositoryService.RepositoryInput(
                "jobs", "Jobs", "https://github.com/acme/jobs.git", "main", null, null);

        ResponseEntity<?> response = controller.registerRepository(input, "alice");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(repositories, never()).register(input, "alice");
    }

    @Test
    void sourceTrustAndVerificationEvidenceAreRestrictedToManagers() {
        ManagedSparkCatalogService catalog = mock(ManagedSparkCatalogService.class);
        GitRepositoryService repositories = mock(GitRepositoryService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.mayManage("alice")).thenReturn(false);
        ManagedSparkController controller = new ManagedSparkController(
                catalog, mock(SparkBatchExecutionAdapter.class),
                mock(SparkResourceQueueService.class), repositories, authorization);
        GitRepositoryService.RepositoryTrustInput trust =
                new GitRepositoryService.RepositoryTrustInput("required", java.util.List.of("a".repeat(40)));

        assertEquals(HttpStatus.FORBIDDEN,
                controller.changeRepositoryTrust(1L, trust, "alice").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.verifyArtifact(2L, "alice").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller.artifactVerifications(2L, "alice").getStatusCode());

        verify(repositories, never()).changeTrust(1L, trust, "alice");
        verify(catalog, never()).verifyArtifact(2L, "alice");
        verify(catalog, never()).verifications(2L);
    }

    @Test
    void probesRequestedFlinkGatewayEndpoint() {
        FlinkSqlExecutionClient flink = mock(FlinkSqlExecutionClient.class);
        when(flink.health("http://flink-task:8083"))
                .thenReturn(new FlinkSqlExecutionClient.GatewayHealth("UP", "1.20.1", "http://flink-task:8083"));
        ManagedSparkController controller = new ManagedSparkController(
                mock(ManagedSparkCatalogService.class), mock(SparkBatchExecutionAdapter.class),
                mock(SparkResourceQueueService.class), mock(GitRepositoryService.class),
                mock(WorkflowAuthorizationService.class), flink);

        ResponseEntity<?> response = controller.flinkHealth("http://flink-task:8083/");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(flink).health("http://flink-task:8083");
    }

    @Test
    void rejectsCredentialedOrNonHttpFlinkEndpointBeforeProbing() {
        FlinkSqlExecutionClient flink = mock(FlinkSqlExecutionClient.class);
        ManagedSparkController controller = new ManagedSparkController(
                mock(ManagedSparkCatalogService.class), mock(SparkBatchExecutionAdapter.class),
                mock(SparkResourceQueueService.class), mock(GitRepositoryService.class),
                mock(WorkflowAuthorizationService.class), flink);

        ResponseEntity<?> response = controller.flinkHealth("file:///tmp/flink");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(flink, never()).health(org.mockito.ArgumentMatchers.anyString());
    }
}
