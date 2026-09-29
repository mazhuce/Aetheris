package com.aetheris.metadata.entity;

import com.aetheris.metadata.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName(value = "lakehouse_maintenance_job", schema = "metadata", autoResultMap = true)
public class LakehouseMaintenanceJob {
    @TableId(type = IdType.AUTO) private Long id;
    private Long lakehouseTableId;
    private String operation;
    private String idempotencyKey;
    private String requestedSnapshotId;
    private String status;
    @TableField(typeHandler = JsonbTypeHandler.class) private String requestJson;
    @TableField(typeHandler = JsonbTypeHandler.class) private String resultJson;
    private String executorRequestKey;
    private String remoteHandle;
    private String requestedBy;
    private String lastError;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getLakehouseTableId() { return lakehouseTableId; }
    public void setLakehouseTableId(Long value) { lakehouseTableId = value; }
    public String getOperation() { return operation; }
    public void setOperation(String value) { operation = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestedSnapshotId() { return requestedSnapshotId; }
    public void setRequestedSnapshotId(String value) { requestedSnapshotId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String value) { requestJson = value; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String value) { resultJson = value; }
    public String getExecutorRequestKey() { return executorRequestKey; }
    public void setExecutorRequestKey(String value) { executorRequestKey = value; }
    public String getRemoteHandle() { return remoteHandle; }
    public void setRemoteHandle(String value) { remoteHandle = value; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String value) { requestedBy = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime value) { startedAt = value; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime value) { finishedAt = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
}
