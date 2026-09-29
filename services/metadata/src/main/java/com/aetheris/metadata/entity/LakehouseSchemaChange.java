package com.aetheris.metadata.entity;

import com.aetheris.metadata.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName(value = "lakehouse_schema_change", schema = "metadata")
public class LakehouseSchemaChange {
    @TableId(type = IdType.AUTO) private Long id;
    private Long lakehouseTableId;
    private String operation;
    private String columnName;
    private String newColumnName;
    private String fromType;
    private String toType;
    private String decision;
    private String status;
    private String reason;
    private String requestedBy;
    private String reviewedBy;
    private OffsetDateTime reviewedAt;
    private OffsetDateTime appliedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String executionRequestKey;
    private String remoteHandle;
    @TableField(typeHandler = JsonbTypeHandler.class) private String executionResultJson;
    private String executionError;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getLakehouseTableId() { return lakehouseTableId; }
    public void setLakehouseTableId(Long value) { lakehouseTableId = value; }
    public String getOperation() { return operation; }
    public void setOperation(String value) { operation = value; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String value) { columnName = value; }
    public String getNewColumnName() { return newColumnName; }
    public void setNewColumnName(String value) { newColumnName = value; }
    public String getFromType() { return fromType; }
    public void setFromType(String value) { fromType = value; }
    public String getToType() { return toType; }
    public void setToType(String value) { toType = value; }
    public String getDecision() { return decision; }
    public void setDecision(String value) { decision = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String value) { requestedBy = value; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String value) { reviewedBy = value; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(OffsetDateTime value) { reviewedAt = value; }
    public OffsetDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(OffsetDateTime value) { appliedAt = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    public String getExecutionRequestKey() { return executionRequestKey; }
    public void setExecutionRequestKey(String value) { executionRequestKey = value; }
    public String getRemoteHandle() { return remoteHandle; }
    public void setRemoteHandle(String value) { remoteHandle = value; }
    public String getExecutionResultJson() { return executionResultJson; }
    public void setExecutionResultJson(String value) { executionResultJson = value; }
    public String getExecutionError() { return executionError; }
    public void setExecutionError(String value) { executionError = value; }
}
