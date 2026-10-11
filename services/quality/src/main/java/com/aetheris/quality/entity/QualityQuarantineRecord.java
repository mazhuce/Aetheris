package com.aetheris.quality.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** 质量规则超阈值后的隔离记录；实际违规行仍留在源表，避免质量服务复制业务数据。 */
@TableName(value = "quality_quarantine_record", schema = "quality")
public class QualityQuarantineRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resultId;
    private Long ruleId;
    private String targetTable;
    private Long violationCount;
    private Long thresholdValue;
    private String reason;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime releasedAt;
    private String releasedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getResultId() { return resultId; }
    public void setResultId(Long resultId) { this.resultId = resultId; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public Long getViolationCount() { return violationCount; }
    public void setViolationCount(Long violationCount) { this.violationCount = violationCount; }
    public Long getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(Long thresholdValue) { this.thresholdValue = thresholdValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }
    public String getReleasedBy() { return releasedBy; }
    public void setReleasedBy(String releasedBy) { this.releasedBy = releasedBy; }
}
