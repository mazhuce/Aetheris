package com.aetheris.workflow.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 手动触发任务的请求。
 *
 * @param bizDate 业务日期 yyyy-MM-dd；为空时默认取昨天（与定时触发口径一致）
 */
public record TaskRunRequest(
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "业务日期格式应为 yyyy-MM-dd")
        String bizDate) {
}
