package com.aetheris.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName(value = "lakehouse_snapshot_ref", schema = "metadata")
public class LakehouseSnapshotRef {
    @TableId(type = IdType.AUTO) private Long id;
    private Long lakehouseTableId;
    private String refName;
    private String refType;
    private String snapshotId;
    @TableField("protected") private Boolean protectedRef;
    private String createdBy;
    private String updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getLakehouseTableId() { return lakehouseTableId; }
    public void setLakehouseTableId(Long value) { lakehouseTableId = value; }
    public String getRefName() { return refName; }
    public void setRefName(String value) { refName = value; }
    public String getRefType() { return refType; }
    public void setRefType(String value) { refType = value; }
    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String value) { snapshotId = value; }
    public Boolean getProtected() { return protectedRef; }
    public void setProtected(Boolean value) { protectedRef = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String value) { updatedBy = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
}
