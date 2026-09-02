package com.aetheris.ingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

@TableName(value = "ingestion_task", schema = "ingestion")
public class IngestionTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    @NotBlank private String name;
    private String sourceType;
    @NotNull private Long sourceDatasourceId;
    @NotNull private Long targetDatasourceId;
    @NotBlank private String sourceSql;
    @NotBlank private String targetTable;
    private String writeMode;
    private Integer batchSize;
    private String scheduleCron;
    private String sourceConfig;
    private String status;
    private String lastRunStatus;
    private OffsetDateTime lastRunAt;
    private Long lastRunRows;
    private String lastError;
    private String description;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceDatasourceId() { return sourceDatasourceId; }
    public void setSourceDatasourceId(Long sourceDatasourceId) { this.sourceDatasourceId = sourceDatasourceId; }
    public Long getTargetDatasourceId() { return targetDatasourceId; }
    public void setTargetDatasourceId(Long targetDatasourceId) { this.targetDatasourceId = targetDatasourceId; }
    public String getSourceSql() { return sourceSql; }
    public void setSourceSql(String sourceSql) { this.sourceSql = sourceSql; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public String getWriteMode() { return writeMode; }
    public void setWriteMode(String writeMode) { this.writeMode = writeMode; }
    public Integer getBatchSize() { return batchSize; }
    public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }
    public String getScheduleCron() { return scheduleCron; }
    public void setScheduleCron(String scheduleCron) { this.scheduleCron = scheduleCron; }
    public String getSourceConfig() { return sourceConfig; }
    public void setSourceConfig(String sourceConfig) { this.sourceConfig = sourceConfig; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }
    public OffsetDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(OffsetDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public Long getLastRunRows() { return lastRunRows; }
    public void setLastRunRows(Long lastRunRows) { this.lastRunRows = lastRunRows; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
