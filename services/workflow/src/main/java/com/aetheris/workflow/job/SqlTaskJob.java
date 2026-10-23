package com.aetheris.workflow.job;

import com.aetheris.workflow.service.TaskExecutionService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;


/**
 * Quartz 触发的 SQL 任务执行作业。
 *
 * <p>{@code @DisallowConcurrentExecution} 保证同一个 JobDetail 不会并发执行：
 * 上一次还没跑完时，新的触发会等待而不是叠加。配合 JDBC JobStore 的行锁，
 * 多实例部署下同一次触发也只会被一个实例领取。
 *
 * <p>业务日期取「昨天」而非今天：数仓加工通常在凌晨跑前一天的完整数据，
 * 用今天会得到不完整的分区。需要补跑历史日期时走手动触发接口指定 bizDate。
 */
@DisallowConcurrentExecution
public class SqlTaskJob extends QuartzJobBean {

    public static final String TASK_ID = "taskId";
    private static final Logger log = LoggerFactory.getLogger(SqlTaskJob.class);

    private final TaskExecutionService executionService;
    private final com.aetheris.workflow.config.BusinessCalendar businessCalendar;

    public SqlTaskJob(TaskExecutionService executionService) {
        this(executionService, new com.aetheris.workflow.config.BusinessCalendar());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SqlTaskJob(TaskExecutionService executionService,
                      com.aetheris.workflow.config.BusinessCalendar businessCalendar) {
        this.executionService = executionService;
        this.businessCalendar = businessCalendar;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        Long taskId = context.getMergedJobDataMap().getLong(TASK_ID);
        String bizDate = businessCalendar.previousDate(context.getScheduledFireTime() == null
                ? null : context.getScheduledFireTime().toInstant());

        try {
            if(!context.getMergedJobDataMap().containsKey("scheduleVersion"))throw new IllegalStateException("旧调度缺少绑定generation");
            long generation=context.getMergedJobDataMap().getLong("scheduleVersion");
            if(generation<=0)throw new IllegalStateException("调度generation无效");
            executionService.executeScheduled(taskId, bizDate, generation);
        } catch (RuntimeException error) {
            // 不向 Quartz 抛异常：任务失败已记录在实例与日志表里，是正常的业务结果。
            // 抛出去会让 Quartz 按 misfire 策略重试，与我们自己的 max_retries 叠加，
            // 造成不可控的重复执行。
            log.warn("Scheduled task {} failed for bizDate {}", taskId, bizDate, error);
        }
    }
}
