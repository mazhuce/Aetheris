package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.job.SqlTaskJob;
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
 * 把任务定义同步到 Quartz 调度器。
 *
 * <p>调度状态存在 Quartz 的 JDBC JobStore 里，任务定义存在 {@code task} 表里，两者需保持一致：
 * 任务新建/改 cron/启停/删除时都要同步，否则会出现「界面显示已停用但仍在跑」这类不一致。
 *
 * <p>misfire 策略选 {@code withMisfireHandlingInstructionDoNothing}：服务停机期间错过的触发
 * 直接放弃，不补跑。原因是数仓任务的业务日期由触发时刻推导，补跑会用错误的日期执行；
 * 需要补数据时应显式指定 bizDate 手动触发，这样意图明确、结果可预期。
 */
@Service
public class TaskSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerService.class);
    private static final String JOB_GROUP = "aetheris-sql-tasks";

    private final Scheduler scheduler;

    public TaskSchedulerService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 按任务当前状态同步调度。
     *
     * <p>停用或未配置 cron 的任务会被移除调度，因此本方法同时承担「取消调度」的职责，
     * 调用方不需要自己判断该注册还是该删除。
     */
    public void sync(Task task) {
        try {
            boolean schedulable = "enabled".equals(task.getStatus())
                    && StringUtils.hasText(task.getCronExpression());
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

    private void register(Task task) throws SchedulerException {
        JobKey jobKey = jobKey(task.getId());
        TriggerKey triggerKey = triggerKey(task.getId());

        JobDetail jobDetail = JobBuilder.newJob(SqlTaskJob.class)
                .withIdentity(jobKey)
                .usingJobData(SqlTaskJob.TASK_ID, task.getId())
                .withDescription(task.getName())
                // 持久化：没有触发器关联时也保留 JobDetail，便于停用后重新启用。
                .storeDurably()
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(CronScheduleBuilder
                        .cronSchedule(task.getCronExpression())
                        // 时区固定为 Asia/Shanghai，与业务日期口径一致；
                        // 跟随容器默认时区会导致部署环境不同而触发时刻漂移。
                        .inTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))
                        .withMisfireHandlingInstructionDoNothing())
                .build();

        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey);
        }
        scheduler.scheduleJob(jobDetail, trigger);
        log.info("Task {} scheduled with cron {}", task.getId(), task.getCronExpression());
    }

    /** 移除调度；任务不在调度中时静默返回，便于重复调用。 */
    public void unschedule(Long taskId) {
        try {
            JobKey jobKey = jobKey(taskId);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("Task {} unscheduled", taskId);
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
            log.debug("Unable to read next fire time for task {}", taskId, error);
            return null;
        }
    }

    /**
     * 校验 cron 表达式。
     *
     * <p>在保存任务前校验，而不是等到注册调度时才失败——后者会让任务已入库但不会执行。
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
