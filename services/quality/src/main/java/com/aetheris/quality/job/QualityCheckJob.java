package com.aetheris.quality.job;

import com.aetheris.quality.service.QualityExecutionService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz 触发的质量规则检查作业。
 *
 * <p>{@code @DisallowConcurrentExecution} 保证同一条规则不会并发执行：
 * 上一次检查还没跑完时，新的触发会等待，配合结果表的 running 唯一索引双重防重。
 *
 * <p>检查未通过（违规数超阈值）不算异常，是正常业务结果，会落库到
 * {@code quality_check_result}；只有执行本身失败才记录告警日志。
 */
@DisallowConcurrentExecution
public class QualityCheckJob extends QuartzJobBean {

    public static final String RULE_ID = "ruleId";
    private static final Logger log = LoggerFactory.getLogger(QualityCheckJob.class);

    private final QualityExecutionService executionService;

    public QualityCheckJob(QualityExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        Long ruleId = context.getMergedJobDataMap().getLong(RULE_ID);
        try {
            executionService.execute(ruleId, "scheduler");
        } catch (RuntimeException error) {
            // 不向 Quartz 抛异常：失败已记录在结果表与规则表的 last_error 里，
            // 抛出去会触发 Quartz 的重试机制，造成不可控的重复执行。
            log.warn("Scheduled quality check for rule {} failed", ruleId, error);
        }
    }
}
