package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.service.IngestionExecutionService;
import com.aetheris.ingestion.service.IngestionRunService;
import com.aetheris.ingestion.service.IngestionSchedulerService;
import com.aetheris.ingestion.service.IngestionTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionTaskControllerTest {

    private final IngestionTaskService service = mock(IngestionTaskService.class);
    private final IngestionRunService runService = mock(IngestionRunService.class);
    private final IngestionExecutionService executionService = mock(IngestionExecutionService.class);
    private final IngestionTaskController controller = new IngestionTaskController(service, runService, executionService, mock(IngestionSchedulerService.class));

    private IngestionTask task(Long id, String name) {
        IngestionTask task = new IngestionTask();
        task.setId(id);
        task.setName(name);
        task.setSourceType("postgres");
        task.setSourceDatasourceId(1L);
        task.setTargetDatasourceId(2L);
        task.setSourceSql("SELECT id FROM public.orders");
        task.setTargetTable("ods.orders");
        task.setStatus("idle");
        return task;
    }

    @Test
    void listReturnsAllTasks() {
        when(service.list()).thenReturn(List.of(task(1L, "订单同步")));
        List<IngestionTask> result = controller.list();
        assertEquals(1, result.size());
        assertEquals("订单同步", result.get(0).getName());
    }

    @Test
    void getByIdReturnsTask() {
        when(service.getById(1L)).thenReturn(task(1L, "订单同步"));
        ResponseEntity<IngestionTask> response = controller.getById(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("订单同步", response.getBody().getName());
    }

    @Test
    void getByIdReturns404WhenMissing() {
        when(service.getById(99L)).thenReturn(null);
        assertEquals(HttpStatus.NOT_FOUND, controller.getById(99L).getStatusCode());
    }

    @Test
    void createSavesAndReturnsTask() {
        IngestionTask input = task(null, "用户同步");
        when(service.save(input)).thenReturn(true);
        assertEquals(input, controller.create(input));
        verify(service).save(input);
    }

    @Test
    void updateAppliesPathId() {
        IngestionTask input = task(null, "重命名任务");
        when(service.updateById(input)).thenReturn(true);
        when(service.getById(5L)).thenReturn(task(5L, "重命名任务"));

        ResponseEntity<IngestionTask> response = controller.update(5L, input);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(5L, input.getId());
        verify(service).updateById(input);
    }

    @Test
    void updateReturns404WhenNothingChanged() {
        when(service.updateById(any(IngestionTask.class))).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND, controller.update(99L, task(null, "x")).getStatusCode());
    }

    @Test
    void deleteReturns204WhenRemoved() {
        when(service.removeById(1L)).thenReturn(true);
        assertEquals(HttpStatus.NO_CONTENT, controller.delete(1L).getStatusCode());
    }

    @Test
    void deleteReturns404WhenMissing() {
        when(service.removeById(99L)).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND, controller.delete(99L).getStatusCode());
    }

    @Test
    void runReturnsPersistedExecutionResult() {
        IngestionRun run = new IngestionRun();
        run.setId(9L);
        run.setTaskId(7L);
        run.setStatus("success");
        run.setRowsWritten(42L);
        when(executionService.execute(7L, "admin")).thenReturn(run);

        ResponseEntity<?> response = controller.run(7L, "admin");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(run, response.getBody());
        verify(executionService).execute(7L, "admin");
    }

    @Test
    void runReturns400WhenTaskIsInvalid() {
        when(executionService.execute(99L, "admin"))
                .thenThrow(new IllegalArgumentException("接入任务不存在"));
        assertEquals(HttpStatus.BAD_REQUEST, controller.run(99L, "admin").getStatusCode());
    }

    @Test
    void runReturnsConflictWhenAlreadyRunning() {
        when(executionService.execute(2L, "admin"))
                .thenThrow(new IllegalStateException("该任务已有运行中的实例"));
        assertEquals(HttpStatus.CONFLICT, controller.run(2L, "admin").getStatusCode());
    }
}
