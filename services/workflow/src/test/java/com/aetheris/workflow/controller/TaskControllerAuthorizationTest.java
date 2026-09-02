package com.aetheris.workflow.controller;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.service.SqlExecutionClient;
import com.aetheris.workflow.service.TaskDependencyService;
import com.aetheris.workflow.service.TaskExecutionService;
import com.aetheris.workflow.service.TaskInstanceService;
import com.aetheris.workflow.service.TaskLogService;
import com.aetheris.workflow.service.TaskSchedulerService;
import com.aetheris.workflow.service.TaskService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class TaskControllerAuthorizationTest {

    private final TaskController controller = new TaskController(
            mock(TaskService.class),
            mock(TaskInstanceService.class),
            mock(TaskLogService.class),
            mock(TaskDependencyService.class),
            mock(TaskExecutionService.class),
            mock(TaskSchedulerService.class),
            mock(SqlExecutionClient.class));

    @Test
    void viewerCannotMutateOrRunTasks() {
        Task task = new Task();

        assertThrows(TaskController.ForbiddenException.class,
                () -> controller.create(task, "alice", "viewer"));
        assertThrows(TaskController.ForbiddenException.class,
                () -> controller.update(1L, task, "viewer"));
        assertThrows(TaskController.ForbiddenException.class,
                () -> controller.delete(1L, "viewer"));
        assertThrows(TaskController.ForbiddenException.class,
                () -> controller.run(1L, null, "alice", "viewer"));
        assertThrows(TaskController.ForbiddenException.class,
                () -> controller.addDependency(1L, 2L, "viewer"));
        assertThrows(TaskController.ForbiddenException.class,
                () -> controller.removeDependency(1L, 2L, "viewer"));
    }
}
