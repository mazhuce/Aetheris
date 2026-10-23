package com.aetheris.workflow.dto;


/**
 * 手动触发任务的请求。
 *
 * @param bizDate 业务日期 yyyy-MM-dd；为空时默认取昨天（与定时触发口径一致）
 */
public record TaskRunRequest(
        @ValidBusinessDate
        String bizDate) {
}
