package com.aetheris.quality.service;

import com.aetheris.quality.entity.QualityRule;
import com.aetheris.quality.job.QualityCheckJob;
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
 * 把质量规则定义同步到 Quartz 调度器（结构与 workflow 的 TaskSchedulerService 一致）。
 *
 * <p>规则定义存在 {@code quality_rule} 表，调度状态存在 Quartz 的 JDBC JobStore 里，
 * 两者需保持一致：规则新建/改 cron/启停/删除时都要同步，否则会出现
 * 「界面显示已停用但仍在跑」这类不一致。
 *
 * <p>misfire 策略选 {@code withMisfireHandlingInstructionDoNothing}：
 * 服务停机期间错过的检查直接放弃，不补跑。质量检查的意义是反映当前数据状态，
 * 过期的检查结果没有回放价值。
 */
@Service
public class QualitySchedulerService {

    private static final Logger log = LoggerFactory.getLogger(QualitySchedulerService.class);
    private static final String JOB_GROUP = "aetheris-quality-rules";

    private final Scheduler scheduler;

    public QualitySchedulerService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 按规则当前状态同步调度。停用或未配置 cron 的规则会被移除调度，
     * 因此本方法同时承担「取消调度」的职责。
     */
    public void sync(QualityRule rule) {
        try {
            boolean schedulable = "enabled".equals(rule.getStatus())
                    && StringUtils.hasText(rule.getCronExpression());
            if (!schedulable) {
                unschedule(rule.getId());
                return;
            }
            register(rule);
        } catch (SchedulerException error) {
            // 调度同步失败必须让调用方知道：否则规则看起来保存成功了，实际不会自动执行。
            throw new IllegalStateException("调度同步失败：" + error.getMessage(), error);
        }
    }

    private void register(QualityRule rule) throws SchedulerException {
        JobKey jobKey = jobKey(rule.getId());
        TriggerKey triggerKey = triggerKey(rule.getId());

        JobDetail jobDetail = JobBuilder.newJob(QualityCheckJob.class)
                .withIdentity(jobKey)
                .usingJobData(QualityCheckJob.RULE_ID, rule.getId())
                .withDescription(rule.getName())
                // 持久化：没有触发器关联时也保留 JobDetail，便于停用后重新启用。
                .storeDurably()
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(CronScheduleBuilder
                        .cronSchedule(rule.getCronExpression())
                        // 时区固定为 Asia/Shanghai，与 workflow 的调度口径一致。
                        .inTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))
                        .withMisfireHandlingInstructionDoNothing())
                .build();

        if (scheduler.checkExists(jobKey)) {
            scheduler.deleteJob(jobKey);
        }
        scheduler.scheduleJob(jobDetail, trigger);
        log.info("Quality rule {} scheduled with cron {}", rule.getId(), rule.getCronExpression());
    }

    /** 移除调度；规则不在调度中时静默返回，便于重复调用。 */
    public void unschedule(Long ruleId) {
        try {
            JobKey jobKey = jobKey(ruleId);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("Quality rule {} unscheduled", ruleId);
            }
        } catch (SchedulerException error) {
            throw new IllegalStateException("取消调度失败：" + error.getMessage(), error);
        }
    }

    /** 下次触发时刻；未配置调度时返回 null。 */
    public Date nextFireTime(Long ruleId) {
        try {
            Trigger trigger = scheduler.getTrigger(triggerKey(ruleId));
            return trigger == null ? null : trigger.getNextFireTime();
        } catch (SchedulerException error) {
            log.debug("Unable to read next fire time for quality rule {}", ruleId, error);
            return null;
        }
    }

    /**
     * 校验 cron 表达式。在保存规则前校验，而不是等到注册调度时才失败——
     * 后者会让规则已入库但不会执行。
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

    private JobKey jobKey(Long ruleId) {
        return JobKey.jobKey("rule-" + ruleId, JOB_GROUP);
    }

    private TriggerKey triggerKey(Long ruleId) {
        return TriggerKey.triggerKey("trigger-" + ruleId, JOB_GROUP);
    }
}
