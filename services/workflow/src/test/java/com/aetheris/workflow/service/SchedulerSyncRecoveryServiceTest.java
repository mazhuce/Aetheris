package com.aetheris.workflow.service;
import com.aetheris.workflow.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
class SchedulerSyncRecoveryServiceTest {
    @Test void oldUnscheduleCannotOverrideCurrentEnabledDefinition() {
        TaskService tasks=mock(TaskService.class); TaskSchedulerService scheduler=mock(TaskSchedulerService.class);
        Task current=new Task(); current.setId(1L); current.setStatus("enabled"); current.setCronExpression("0 0 2 * * ?");
        when(tasks.getById(1L)).thenReturn(current);
        var recovery=new SchedulerSyncRecoveryService(mock(JdbcTemplate.class),tasks,scheduler,8,30);
        recovery.reconcile(new SchedulerSyncRecoveryService.RecoveryEntry(1,1,"unschedule"));
        verify(scheduler).sync(current); verify(scheduler,never()).unschedule(1L);
    }
    @Test void deletedDefinitionIsAlwaysUnsheduledRegardlessOfOldOperation() {
        TaskSchedulerService scheduler=mock(TaskSchedulerService.class);
        var recovery=new SchedulerSyncRecoveryService(mock(JdbcTemplate.class),mock(TaskService.class),scheduler,8,30);
        recovery.reconcile(new SchedulerSyncRecoveryService.RecoveryEntry(1,1,"sync"));
        verify(scheduler).unschedule(1L);
    }
}
