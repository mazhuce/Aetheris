package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.service.IngestionTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionTaskControllerTest {

    private final IngestionTaskService service = mock(IngestionTaskService.class);
    private final IngestionTaskController controller = new IngestionTaskController(service);

    private IngestionTask task(Long id, String name) {
        IngestionTask task = new IngestionTask();
        task.setId(id);
        task.setName(name);
        task.setSourceType("postgres");
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
    void runMarksTaskSuccess() {
        IngestionTask stored = task(7L, "库存同步");
        when(service.getById(7L)).thenReturn(stored);
        when(service.updateById(stored)).thenReturn(true);

        ResponseEntity<IngestionTask> response = controller.run(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(7L, response.getBody().getId());
        assertEquals("success", stored.getLastRunStatus());
        assertNotNull(stored.getLastRunAt());
        verify(service).updateById(stored);
    }

    @Test
    void runReturns404WhenTaskMissing() {
        when(service.getById(99L)).thenReturn(null);
        assertEquals(HttpStatus.NOT_FOUND, controller.run(99L).getStatusCode());
        verify(service, never()).updateById(any(IngestionTask.class));
    }

    @Test
    void runRecordsTimestampNearNow() {
        IngestionTask stored = task(2L, "延迟校验");
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(5);
        when(service.getById(2L)).thenReturn(stored);
        when(service.updateById(stored)).thenReturn(true);

        controller.run(2L);

        ArgumentCaptor<IngestionTask> captor = ArgumentCaptor.forClass(IngestionTask.class);
        verify(service).updateById(captor.capture());
        assertEquals("success", captor.getValue().getLastRunStatus());
        assertEquals(true, captor.getValue().getLastRunAt().isAfter(before));
    }
}
