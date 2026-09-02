package com.aetheris.ingestion.job;

import com.aetheris.ingestion.service.IngestionExecutionService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz 触发的数据接入同步作业。
 *
 * <p>{@code @DisallowConcurrentExecution} 保证同一个任务不会并发执行：
 * 大批量搬运可能持续数分钟，重复叠加执行会放大源库压力，
 * 且写侧 replace 模式下并发写入会产生互相覆盖。
 *
 * <p>接入没有自动重试：失败记录在 {@code ingestion_run} 与任务的
 * last_error 里，由人工判断是否补跑（写入类操作自动重试有数据一致性风险）。
 */
@DisallowConcurrentExecution
public class IngestionSyncJob extends QuartzJobBean {

    public static final String TASK_ID = "taskId";
    private static final Logger log = LoggerFactory.getLogger(IngestionSyncJob.class);

    private final IngestionExecutionService executionService;

    public IngestionSyncJob(IngestionExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        Long taskId = context.getMergedJobDataMap().getLong(TASK_ID);
        try {
            executionService.execute(taskId, "scheduler");
        } catch (RuntimeException error) {
            // 不向 Quartz 抛异常：失败已记录在 run 表与任务 last_error 里；
            // 抛出去会触发 Quartz 的重试机制，与写入操作叠加造成重复搬运。
            log.warn("Scheduled ingestion for task {} failed", taskId, error);
        }
    }
}
