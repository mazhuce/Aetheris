package com.aetheris.workflow.controller;

import com.aetheris.workflow.service.PipelineExecutionService;
import com.aetheris.workflow.service.PipelineCancellationService;
import com.aetheris.workflow.service.WorkflowAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineControllerTest {

    @Test
    void listScopesOrdinaryUsersToOwnedOrTriggeredPipelines() {
        PipelineExecutionService pipelines = mock(PipelineExecutionService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.mayManage("alice")).thenReturn(false);
        when(pipelines.list(30, "alice", false)).thenReturn(List.of());

        Object result = new PipelineController(pipelines, mock(PipelineCancellationService.class), authorization)
                .list(30, "alice");

        assertEquals(List.of(), result);
        verify(pipelines).list(30, "alice", false);
    }

    @Test
    void detailRejectsUsersWithoutObjectAccess() {
        PipelineExecutionService pipelines = mock(PipelineExecutionService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.canReadPipeline("alice", 7L)).thenReturn(false);

        ResponseEntity<?> response = new PipelineController(
                pipelines, mock(PipelineCancellationService.class), authorization).detail(7L, "alice");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(pipelines, never()).detail(7L);
    }

    @Test
    void detailReturnsNotFoundForAuthorizedMissingPipeline() {
        PipelineExecutionService pipelines = mock(PipelineExecutionService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.canReadPipeline("admin", 99L)).thenReturn(true);
        when(pipelines.detail(99L)).thenThrow(new IllegalArgumentException("流水线运行不存在"));

        ResponseEntity<?> response = new PipelineController(
                pipelines, mock(PipelineCancellationService.class), authorization).detail(99L, "admin");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void cancelRejectsUsersWithoutObjectAccess() {
        PipelineExecutionService pipelines = mock(PipelineExecutionService.class);
        PipelineCancellationService cancellations = mock(PipelineCancellationService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.canReadPipeline("alice", 7L)).thenReturn(false);

        ResponseEntity<?> response = new PipelineController(pipelines, cancellations, authorization)
                .cancel(7L, "alice");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(cancellations, never()).cancel(7L, "alice");
    }

    @Test
    void retryRejectsUsersWithoutObjectAccess() {
        PipelineExecutionService pipelines = mock(PipelineExecutionService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.canReadPipeline("alice", 7L)).thenReturn(false);

        ResponseEntity<?> response = new PipelineController(
                pipelines, mock(PipelineCancellationService.class), authorization)
                .retry(7L, new PipelineController.RetryRequest(11L), "alice");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(pipelines, never()).retry(7L, 11L, "alice");
    }

    @Test
    void retryReturnsConflictForUnsafeSourceState() {
        PipelineExecutionService pipelines = mock(PipelineExecutionService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.canReadPipeline("alice", 7L)).thenReturn(true);
        when(pipelines.retry(7L, 11L, "alice"))
                .thenThrow(new IllegalStateException("结果待核对的流水线禁止重跑"));

        ResponseEntity<?> response = new PipelineController(
                pipelines, mock(PipelineCancellationService.class), authorization)
                .retry(7L, new PipelineController.RetryRequest(11L), "alice");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }
}
