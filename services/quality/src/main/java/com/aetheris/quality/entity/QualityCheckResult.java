package com.aetheris.quality.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName(value = "quality_check_result", schema = "quality")
public class QualityCheckResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private String status;
    private Long violationCount;
    private Long thresholdValue;
    private Long durationMs;
    private String message;
    private String triggeredBy;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getViolationCount() { return violationCount; }
    public void setViolationCount(Long violationCount) { this.violationCount = violationCount; }
    public Long getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(Long thresholdValue) { this.thresholdValue = thresholdValue; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
