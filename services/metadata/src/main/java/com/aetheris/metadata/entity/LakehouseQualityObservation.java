package com.aetheris.metadata.entity;

import com.aetheris.metadata.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName(value = "lakehouse_quality_observation", schema = "metadata", autoResultMap = true)
public class LakehouseQualityObservation {
    @TableId(type = IdType.AUTO) private Long id;
    private Long lakehouseTableId;
    private String layer;
    private String ruleKey;
    private String status;
    private Long violationCount;
    private Long lateDataCount;
    private Long deadLetterCount;
    private OffsetDateTime watermarkAt;
    private Boolean blocksGold;
    private String runId;
    @TableField(typeHandler = JsonbTypeHandler.class) private String detailsJson;
    private OffsetDateTime observedAt;
    private String observedBy;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getLakehouseTableId() { return lakehouseTableId; }
    public void setLakehouseTableId(Long value) { lakehouseTableId = value; }
    public String getLayer() { return layer; }
    public void setLayer(String value) { layer = value; }
    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String value) { ruleKey = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getViolationCount() { return violationCount; }
    public void setViolationCount(Long value) { violationCount = value; }
    public Long getLateDataCount() { return lateDataCount; }
    public void setLateDataCount(Long value) { lateDataCount = value; }
    public Long getDeadLetterCount() { return deadLetterCount; }
    public void setDeadLetterCount(Long value) { deadLetterCount = value; }
    public OffsetDateTime getWatermarkAt() { return watermarkAt; }
    public void setWatermarkAt(OffsetDateTime value) { watermarkAt = value; }
    public Boolean getBlocksGold() { return blocksGold; }
    public void setBlocksGold(Boolean value) { blocksGold = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getDetailsJson() { return detailsJson; }
    public void setDetailsJson(String value) { detailsJson = value; }
    public OffsetDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(OffsetDateTime value) { observedAt = value; }
    public String getObservedBy() { return observedBy; }
    public void setObservedBy(String value) { observedBy = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
}
