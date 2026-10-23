package com.aetheris.workflow.controller;
import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.service.*;
import com.aetheris.workflow.security.ResourceAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class TaskControllerSchedulingTest {
    @Test void enableRequiresExecuteWhileDisableDoesNot() {
        var tasks=mock(TaskService.class);
        var existing=new Task();existing.setId(8L);existing.setTaskType("quality");existing.setQualityRuleId(7L);existing.setStatus("disabled");
        when(tasks.getById(8L)).thenReturn(existing);
        var permission=mock(ResourceAuthorization.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"execute denied")).when(permission).require("workflow_task",8L,"execute");
        var controller=new TaskController(tasks,mock(TaskInstanceService.class),mock(TaskLogService.class),
                mock(ExecutionAttemptService.class),mock(ExecutionResolutionService.class),mock(TaskDependencyService.class),
                mock(TaskExecutionService.class),mock(TaskSchedulerService.class),mock(SchedulerSyncRecoveryService.class),
                mock(SqlExecutionClient.class),mock(ManagedSparkCatalogService.class),mock(SparkBatchExecutionService.class),
                mock(WorkflowAuthorizationService.class));
        controller.setAuthorization(permission);
        var input=new Task();input.setTaskType("quality");input.setQualityRuleId(7L);input.setStatus("enabled");
        assertThrows(ResponseStatusException.class,()->controller.update(8L,input));
        existing.setStatus("enabled");input.setStatus("disabled");
        assertEquals(HttpStatus.CONFLICT,controller.update(8L,input).getStatusCode()); // mocked CAS rejects, authorization permits stopping.
        verify(permission,times(1)).require("workflow_task",8L,"execute");
    }
    @Test void cronChangeRequiresExecuteBeforeSavingDefinition() {
        var tasks=mock(TaskService.class);
        var existing=new Task(); existing.setId(8L); existing.setTaskType("quality");
        existing.setQualityRuleId(7L);existing.setStatus("enabled");existing.setCronExpression("0 0 2 * * ?");
        var input=new Task();input.setTaskType("quality");input.setQualityRuleId(7L);input.setCronExpression("0 0 3 * * ?");
        when(tasks.getById(8L)).thenReturn(existing);
        var permission=mock(ResourceAuthorization.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"execute denied")).when(permission).require("workflow_task",8L,"execute");
        var controller=new TaskController(tasks,mock(TaskInstanceService.class),mock(TaskLogService.class),
                mock(ExecutionAttemptService.class),mock(ExecutionResolutionService.class),mock(TaskDependencyService.class),
                mock(TaskExecutionService.class),mock(TaskSchedulerService.class),mock(SchedulerSyncRecoveryService.class),
                mock(SqlExecutionClient.class),mock(ManagedSparkCatalogService.class),mock(SparkBatchExecutionService.class),
                mock(WorkflowAuthorizationService.class));
        controller.setAuthorization(permission);
        assertThrows(ResponseStatusException.class,()->controller.update(8L,input));
        verify(tasks,never()).update(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
}
