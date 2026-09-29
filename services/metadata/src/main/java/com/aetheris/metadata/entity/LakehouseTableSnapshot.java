package com.aetheris.metadata.entity;

import com.aetheris.metadata.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName(value = "lakehouse_table_snapshot", schema = "metadata", autoResultMap = true)
public class LakehouseTableSnapshot {
    @TableId(type = IdType.AUTO) private Long id;
    private Long lakehouseTableId;
    private String snapshotId;
    private String parentSnapshotId;
    private String operation;
    private OffsetDateTime committedAt;
    private Long rowCount;
    private Long fileCount;
    private Long totalBytes;
    private Integer schemaVersion;
    private String manifestLocation;
    @TableField(typeHandler = JsonbTypeHandler.class) private String metadataJson;
    private String createdBy;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getLakehouseTableId() { return lakehouseTableId; }
    public void setLakehouseTableId(Long value) { lakehouseTableId = value; }
    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String value) { snapshotId = value; }
    public String getParentSnapshotId() { return parentSnapshotId; }
    public void setParentSnapshotId(String value) { parentSnapshotId = value; }
    public String getOperation() { return operation; }
    public void setOperation(String value) { operation = value; }
    public OffsetDateTime getCommittedAt() { return committedAt; }
    public void setCommittedAt(OffsetDateTime value) { committedAt = value; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long value) { rowCount = value; }
    public Long getFileCount() { return fileCount; }
    public void setFileCount(Long value) { fileCount = value; }
    public Long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(Long value) { totalBytes = value; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer value) { schemaVersion = value; }
    public String getManifestLocation() { return manifestLocation; }
    public void setManifestLocation(String value) { manifestLocation = value; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String value) { metadataJson = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
}
