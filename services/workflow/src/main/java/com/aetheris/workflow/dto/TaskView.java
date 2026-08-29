package com.aetheris.workflow.dto;

import com.aetheris.workflow.entity.Task;

import java.time.OffsetDateTime;

/**
 * 任务详情视图：任务定义 + 调度状态 + 最近一次运行结果。
 *
 * <p>把这三部分合成一个响应，避免界面为一个列表发起 N+1 次请求。
 *
 * @param task           任务定义
 * @param nextFireTime   下次触发时刻；未配置 cron 或已停用时为 null
 * @param lastStatus     最近一次实例状态
 * @param lastBizDate    最近一次实例的业务日期
 * @param lastFinishedAt 最近一次结束时刻
 * @param upstreamCount  上游依赖数量
 */
public record TaskView(
        Task task,
        OffsetDateTime nextFireTime,
        String lastStatus,
        String lastBizDate,
        OffsetDateTime lastFinishedAt,
        int upstreamCount) {
}
