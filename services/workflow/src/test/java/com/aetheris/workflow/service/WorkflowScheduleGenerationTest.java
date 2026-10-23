package com.aetheris.workflow.service;
import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.job.SqlTaskJob;
import com.aetheris.workflow.config.BusinessCalendar;
import org.junit.jupiter.api.Test;
import org.quartz.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class WorkflowScheduleGenerationTest {
    @Test void quartzJobCarriesDatabaseGenerationRatherThanCallerEntity() throws Exception {
        var quartz=mock(Scheduler.class);var jdbc=mock(JdbcTemplate.class);var task=new Task();task.setId(8L);task.setName("fixed SQL");task.setScheduleVersion(1L);
        when(jdbc.queryForList(anyString(),eq(8L))).thenReturn(List.of(Map.of("schedule_version",7L,"cron_expression","0 0 3 * * ?","status","enabled","task_type","sql","execution_approval","{}","cron_actor","{}","execution_revision",2L,"cron_approved_execution_revision",2L)));
        var scheduler=new TaskSchedulerService(quartz,new BusinessCalendar(),jdbc);scheduler.sync(task);
        var job=org.mockito.ArgumentCaptor.forClass(JobDetail.class);verify(quartz).scheduleJob(job.capture(),any(Trigger.class));assertEquals(7,job.getValue().getJobDataMap().getLong("scheduleVersion"));assertEquals(8,job.getValue().getJobDataMap().getLong(SqlTaskJob.TASK_ID));
    }
    @Test void unapprovedOrUnboundDefinitionRemovesScheduleWithoutRegistering() throws Exception {
        var quartz=mock(Scheduler.class);var jdbc=mock(JdbcTemplate.class);var task=new Task();task.setId(8L);
        var row=new HashMap<String,Object>();row.put("schedule_version",7L);row.put("cron_expression","0 0 3 * * ?");row.put("status","enabled");row.put("task_type","sql");row.put("execution_revision",2L);row.put("cron_approved_execution_revision",2L);
        when(jdbc.queryForList(anyString(),eq(8L))).thenReturn(List.of(row));when(quartz.checkExists(any(JobKey.class))).thenReturn(true);
        new TaskSchedulerService(quartz,new BusinessCalendar(),jdbc).sync(task);verify(quartz).deleteJob(any(JobKey.class));verify(quartz,never()).scheduleJob(any(JobDetail.class),any(Trigger.class));
    }
    @Test void jobForwardsItsPersistedGenerationAndNeverASchedulerUsername() throws Exception {
        var service=mock(TaskExecutionService.class);var context=mock(JobExecutionContext.class);var quartz=mock(Scheduler.class);when(context.getScheduler()).thenReturn(quartz);when(quartz.getContext()).thenReturn(new SchedulerContext());var data=new JobDataMap();data.put(SqlTaskJob.TASK_ID,8L);data.put("scheduleVersion",7L);when(context.getMergedJobDataMap()).thenReturn(data);when(context.getScheduledFireTime()).thenReturn(java.sql.Timestamp.valueOf("2026-10-18 02:00:00"));
        new SqlTaskJob(service,new BusinessCalendar()).execute(context);verify(service).executeScheduled(eq(8L),anyString(),eq(7L));verify(service,never()).execute(anyLong(),anyString(),anyString(),anyString());
    }
    @Test void legacyJobWithoutGenerationCannotReachExecution() throws Exception {
        var service=mock(TaskExecutionService.class);var context=mock(JobExecutionContext.class);var quartz=mock(Scheduler.class);when(context.getScheduler()).thenReturn(quartz);when(quartz.getContext()).thenReturn(new SchedulerContext());var data=new JobDataMap();data.put(SqlTaskJob.TASK_ID,8L);when(context.getMergedJobDataMap()).thenReturn(data);
        new SqlTaskJob(service,new BusinessCalendar()).execute(context);verifyNoInteractions(service);
    }
}
