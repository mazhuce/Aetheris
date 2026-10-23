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
    @Test void enableRequiresExecuteWhileDisplayEditKeepsDisabledState() {
        var existing=task(8L,"existing"); existing.setStatus("disabled");
        when(service.getById(8L)).thenReturn(existing);
        var permission=mock(com.aetheris.ingestion.security.ResourceAuthorization.class);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"execute denied"))
                .when(permission).require("ingestion_task",8L,"execute");
        controller.setAuthorization(permission);
        var enable=task(8L,"enable");enable.setStatus("enabled");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.update(8L,enable));
        var rename=task(8L,"renamed");rename.setStatus(null);
        controller.update(8L,rename);
        assertEquals("disabled",rename.getStatus());
        org.mockito.Mockito.verify(service).updateById(rename);
    }
    @Test void cronChangeRequiresTheResourceExecuteGrant() {
        var existing=task(8L,"existing");
        existing.setScheduleCron("0 0 2 * * ?");
        var input=task(8L,"renamed");
        input.setScheduleCron("0 0 3 * * ?");
        when(service.getById(8L)).thenReturn(existing);
        var permission=mock(com.aetheris.ingestion.security.ResourceAuthorization.class);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"execute denied"))
                .when(permission).require("ingestion_task",8L,"execute");
        controller.setAuthorization(permission);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.update(8L,input));
        org.mockito.Mockito.verify(service,org.mockito.Mockito.never()).updateById(input);
    }
    @org.junit.jupiter.api.BeforeEach
    void authorizeBusinessFixture() {
        var authorization = org.mockito.Mockito.mock(com.aetheris.ingestion.security.ResourceAuthorization.class);
        org.mockito.Mockito.doAnswer(call -> call.getArgument(1)).when(authorization)
                .filter(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
        controller.setAuthorization(authorization);
    }

    @Test
    void unsignedDirectReadCannotExposeTaskSql() {
        IngestionTaskService isolated = mock(IngestionTaskService.class);
        when(isolated.list()).thenReturn(List.of(task(1L, "secret")));
        IngestionTaskController guarded = new IngestionTaskController(isolated,
                mock(IngestionRunService.class), mock(IngestionExecutionService.class), mock(IngestionSchedulerService.class));
        assertEquals(List.of(), guarded.list());
    }

    private final IngestionTaskService service = mock(IngestionTaskService.class);
    private final IngestionRunService runService = mock(IngestionRunService.class);
    private final IngestionExecutionService executionService = mock(IngestionExecutionService.class);
    private final IngestionTaskController controller = new IngestionTaskController(service, runService, executionService, mock(IngestionSchedulerService.class));

    @Test
    void unresolvedExecutionReturnsConflictWithoutDeletingTask() {
        when(runService.count(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,controller.delete(1L).getStatusCode());
        org.mockito.Mockito.verify(service,org.mockito.Mockito.never()).removeById(1L);
    }

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
    void runReturnsPersistedExecutionResult() throws Exception {
        var user=trustedUser();
        IngestionRun run = new IngestionRun();
        run.setId(9L);
        run.setTaskId(7L);
        run.setStatus("success");
        run.setRowsWritten(42L);
        when(executionService.executeManual(7L,user,"manual-7",null)).thenReturn(run);

        ResponseEntity<?> response = controller.run(7L,user,"manual-7");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(run, response.getBody());
        verify(executionService).executeManual(7L,user,"manual-7",null);
    }

    @Test
    void runReturns400WhenTaskIsInvalid() throws Exception {
        var user=trustedUser();
        when(executionService.executeManual(99L,user,null,null))
                .thenThrow(new IllegalArgumentException("接入任务不存在"));
        assertEquals(HttpStatus.BAD_REQUEST, controller.run(99L,user,null).getStatusCode());
    }

    @Test
    void runReturnsConflictWhenAlreadyRunning() throws Exception {
        var user=trustedUser();
        when(executionService.executeManual(2L,user,null,null))
                .thenThrow(new IllegalStateException("该任务已有运行中的实例"));
        assertEquals(HttpStatus.CONFLICT, controller.run(2L,user,null).getStatusCode());
    }
    @Test void creatingAnEnabledScheduleRequiresExecuteBeforeSave() {
        var permission=mock(com.aetheris.ingestion.security.ResourceAuthorization.class);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"execute denied"))
                .when(permission).require("ingestion_task",null,"execute");
        controller.setAuthorization(permission);
        var input=task(null,"scheduled");input.setStatus("enabled");input.setScheduleCron("0 0 2 * * ?");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.create(input));
        org.mockito.Mockito.verify(service,org.mockito.Mockito.never()).save(any());
    }
    private com.aetheris.security.servlet.TrustedUser trustedUser() throws Exception {
        try(var fixture=new com.aetheris.ingestion.security.V2IngestionFixture()) {
            var result=new java.util.concurrent.atomic.AtomicReference<com.aetheris.security.servlet.TrustedUser>();
            fixture.request(request->result.set(com.aetheris.security.servlet.TrustedUser.require(request)));
            return java.util.Objects.requireNonNull(result.get());
        }
    }

}
