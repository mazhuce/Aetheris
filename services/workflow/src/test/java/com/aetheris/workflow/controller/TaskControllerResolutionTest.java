package com.aetheris.workflow.controller;

import com.aetheris.workflow.service.ExecutionAttemptService;
import com.aetheris.workflow.service.ExecutionResolutionService;
import com.aetheris.workflow.service.ManagedSparkCatalogService;
import com.aetheris.workflow.service.SparkBatchExecutionService;
import com.aetheris.workflow.service.SqlExecutionClient;
import com.aetheris.workflow.service.TaskDependencyService;
import com.aetheris.workflow.service.TaskExecutionService;
import com.aetheris.workflow.service.TaskInstanceService;
import com.aetheris.workflow.service.TaskLogService;
import com.aetheris.workflow.service.TaskSchedulerService;
import com.aetheris.workflow.service.SchedulerSyncRecoveryService;
import com.aetheris.workflow.service.TaskService;
import com.aetheris.workflow.service.WorkflowAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskControllerResolutionTest {

    @Test
    void resolveRequiresManagerPermissionEvenBehindTheGateway() {
        ExecutionResolutionService resolutions = mock(ExecutionResolutionService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.mayManage("alice")).thenReturn(false);

        ResponseEntity<?> response = controller(resolutions, authorization).resolve(
                7L, new TaskController.ResolutionRequest("success", "checked", "app-1"), "alice");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(resolutions, never()).resolve(7L, "success", "checked", "app-1", "alice");
    }

    @Test
    void resolveMapsConcurrentOrManagedHandleConflictsToHttp409() {
        ExecutionResolutionService resolutions = mock(ExecutionResolutionService.class);
        WorkflowAuthorizationService authorization = mock(WorkflowAuthorizationService.class);
        when(authorization.mayManage("admin")).thenReturn(true);
        when(resolutions.resolve(7L, "failed", "checked", "app-1", "admin"))
                .thenThrow(new ExecutionResolutionService.ResolutionConflictException("必须等待自动对账"));

        ResponseEntity<?> response = controller(resolutions, authorization).resolve(
                7L, new TaskController.ResolutionRequest("failed", "checked", "app-1"), "admin");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    private static TaskController controller(
            ExecutionResolutionService resolutions, WorkflowAuthorizationService authorization) {
        return new TaskController(
                mock(TaskService.class), mock(TaskInstanceService.class), mock(TaskLogService.class),
                mock(ExecutionAttemptService.class), resolutions, mock(TaskDependencyService.class),
                mock(TaskExecutionService.class), mock(TaskSchedulerService.class),
                mock(SchedulerSyncRecoveryService.class), mock(SqlExecutionClient.class),
                mock(ManagedSparkCatalogService.class), mock(SparkBatchExecutionService.class), authorization);
    }
}
