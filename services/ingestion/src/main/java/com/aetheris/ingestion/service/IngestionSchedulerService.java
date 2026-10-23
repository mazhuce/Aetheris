package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.job.IngestionSyncJob;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.TimeZone;

/**
 * 把接入任务定义同步到 Quartz 调度器（结构与 workflow 的 TaskSchedulerService 一致）。
 *
 * <p>任务定义存在 {@code ingestion_task} 表，调度状态存在 Quartz 的 JDBC JobStore 里，
 * 两者需保持一致：任务新建/改 cron/启停/删除时都要同步。
 *
 * <p>misfire 策略选 {@code withMisfireHandlingInstructionDoNothing}：
 * 停机期间错过的触发直接放弃。接入是增量搬运语义（源 SQL 自带过滤条件），
 * 补跑窗口需要人工确认，避免错峰执行放大源库压力。
 */
@Service
public class IngestionSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(IngestionSchedulerService.class);
    private static final String JOB_GROUP = "aetheris-ingestion-tasks";

    private final Scheduler scheduler;

    private final com.aetheris.ingestion.config.BusinessCalendar calendar;
    private final SchedulerSyncOutbox outbox;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    public IngestionSchedulerService(Scheduler scheduler) {
        this.scheduler=scheduler; this.calendar=new com.aetheris.ingestion.config.BusinessCalendar(); this.outbox=null;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public IngestionSchedulerService(Scheduler scheduler, org.springframework.jdbc.core.JdbcTemplate jdbc,
                     org.springframework.transaction.PlatformTransactionManager transactions,
                     com.aetheris.ingestion.config.BusinessCalendar calendar) {
        this.scheduler=scheduler; this.calendar=calendar;
        this.outbox=new SchedulerSyncOutbox(jdbc,new org.springframework.transaction.support.TransactionTemplate(transactions),this::applyDesired);
        this.jdbc=jdbc;
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void restoreDesiredSchedules() {
        if(jdbc!=null) jdbc.update("UPDATE ingestion.scheduler_sync_outbox SET status='pending',next_attempt_at=now() WHERE NOT deleted OR status='pending'");
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${aetheris.scheduler-recovery.interval-ms:5000}",initialDelayString="${aetheris.scheduler-recovery.initial-delay-ms:1000}")
    public void retryPending() { if(outbox!=null) outbox.retryPending(); }
    private void applyDesired(SchedulerSyncOutbox.DesiredSchedule desired) {
        if(desired.deleted() || !desired.enabled() || desired.cron()==null || desired.cron().isBlank()) {
            removeJob(desired.id()); return;
        }
        IngestionTask item=new IngestionTask(); item.setId(desired.id()); item.setName(desired.name());
        item.setStatus("enabled"); item.setScheduleCron(desired.cron()); item.setScheduleVersion(desired.generation());
        try { register(item); } catch(SchedulerException failure) { throw new IllegalStateException("Quartz同步失败",failure); }
    }

    /**
     * 按任务当前状态同步调度。停用或未配置 cron 的任务会被移除调度，
     * 因此本方法同时承担「取消调度」的职责。
     */
    public void sync(IngestionTask task) {
        if(outbox!=null) { outbox.reconcile(task.getId()); return; }
        try {
            boolean schedulable = "enabled".equals(task.getStatus())
                    && StringUtils.hasText(task.getScheduleCron());
            if (!schedulable) {
                unschedule(task.getId());
                return;
            }
            register(task);
        } catch (SchedulerException error) {
            // 调度同步失败必须让调用方知道：否则任务看起来保存成功了，实际不会自动执行。
            throw new IllegalStateException("调度同步失败：" + error.getMessage(), error);
        }
    }

    private void register(IngestionTask task) throws SchedulerException {
        JobKey existingKey=jobKey(task.getId());
        JobDetail existing=scheduler.getJobDetail(existingKey);
        Trigger existingTrigger=scheduler.getTrigger(triggerKey(task.getId()));
        if(existing!=null && existing.getJobDataMap().containsKey("scheduleVersion")
                && existing.getJobDataMap().getLong("scheduleVersion")==task.getScheduleVersion()
                && existingTrigger instanceof org.quartz.CronTrigger cron
                && task.getScheduleCron().equals(cron.getCronExpression())
                && calendar.zone().equals(cron.getTimeZone().toZoneId())) return;
        JobKey jobKey = jobKey(task.getId());
        TriggerKey triggerKey = triggerKey(task.getId());

        JobDetail jobDetail = JobBuilder.newJob(IngestionSyncJob.class)
                .withIdentity(jobKey)
                .usingJobData(IngestionSyncJob.TASK_ID, task.getId())
                .usingJobData("scheduleVersion", task.getScheduleVersion())
                .withDescription(task.getName())
                // 持久化：没有触发器关联时也保留 JobDetail，便于停用后重新启用。
                .storeDurably()
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(CronScheduleBuilder
                        .cronSchedule(task.getScheduleCron())
                        // 时区固定为 Asia/Shanghai，与 workflow 的调度口径一致。
                        .inTimeZone(TimeZone.getTimeZone(calendar.zone()))
                        .withMisfireHandlingInstructionDoNothing())
                .build();

        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey);
        }
        scheduler.scheduleJob(jobDetail, trigger);
        log.info("Ingestion task {} scheduled with cron {}", task.getId(), task.getScheduleCron());
    }

    /** 移除调度；任务不在调度中时静默返回，便于重复调用。 */
    public void unschedule(Long taskId) {
        if(outbox!=null) { outbox.reconcile(taskId); return; }
        removeJob(taskId);
    }
    private void removeJob(Long taskId) {
        try {
            JobKey jobKey = jobKey(taskId);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("Ingestion task {} unscheduled", taskId);
            }
        } catch (SchedulerException error) {
            throw new IllegalStateException("取消调度失败：" + error.getMessage(), error);
        }
    }

    /** 下次触发时刻；未配置调度时返回 null。 */
    public Date nextFireTime(Long taskId) {
        try {
            Trigger trigger = scheduler.getTrigger(triggerKey(taskId));
            return trigger == null ? null : trigger.getNextFireTime();
        } catch (SchedulerException error) {
            log.debug("Unable to read next fire time for ingestion task {}", taskId, error);
            return null;
        }
    }

    /**
     * 校验 cron 表达式。在保存任务前校验，而不是等到注册调度时才失败——
     * 后者会让任务已入库但不会执行。
     */
    public static void validateCron(String cronExpression) {
        if (!StringUtils.hasText(cronExpression)) {
            return;
        }
        if (!CronExpression.isValidExpression(cronExpression)) {
            throw new IllegalArgumentException(
                    "cron 表达式不合法（Quartz 格式为 6 或 7 段，例如 0 0 2 * * ?）");
        }
    }

    private JobKey jobKey(Long taskId) {
        return JobKey.jobKey("task-" + taskId, JOB_GROUP);
    }

    private TriggerKey triggerKey(Long taskId) {
        return TriggerKey.triggerKey("trigger-" + taskId, JOB_GROUP);
    }
}
