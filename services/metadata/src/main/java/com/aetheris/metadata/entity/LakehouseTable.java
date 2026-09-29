package com.aetheris.metadata.entity;

import com.aetheris.metadata.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 受管 Iceberg 表的控制面登记。
 *
 * <p>currentSnapshotId 只表示平台已知的远端快照指针，不代表本服务直接修改了 Iceberg metadata。</p>
 */
@TableName(value = "lakehouse_table", schema = "metadata", autoResultMap = true)
public class LakehouseTable {
    @TableId(type = IdType.AUTO) private Long id;
    private String catalogName;
    private String namespaceName;
    private String tableName;
    private String location;
    private Short formatVersion;
    private String layer;
    private String owner;
    private String status;
    private String currentSnapshotId;
    private Integer currentSchemaVersion;
    private Integer retentionDays;
    @TableField(typeHandler = JsonbTypeHandler.class) private String propertiesJson;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableField(exist = false) private String identifier;

    public String getIdentifier() {
        return catalogName + "." + namespaceName + "." + tableName;
    }

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getCatalogName() { return catalogName; }
    public void setCatalogName(String value) { catalogName = value; }
    public String getNamespaceName() { return namespaceName; }
    public void setNamespaceName(String value) { namespaceName = value; }
    public String getTableName() { return tableName; }
    public void setTableName(String value) { tableName = value; }
    public String getLocation() { return location; }
    public void setLocation(String value) { location = value; }
    public Short getFormatVersion() { return formatVersion; }
    public void setFormatVersion(Short value) { formatVersion = value; }
    public String getLayer() { return layer; }
    public void setLayer(String value) { layer = value; }
    public String getOwner() { return owner; }
    public void setOwner(String value) { owner = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getCurrentSnapshotId() { return currentSnapshotId; }
    public void setCurrentSnapshotId(String value) { currentSnapshotId = value; }
    public Integer getCurrentSchemaVersion() { return currentSchemaVersion; }
    public void setCurrentSchemaVersion(Integer value) { currentSchemaVersion = value; }
    public Integer getRetentionDays() { return retentionDays; }
    public void setRetentionDays(Integer value) { retentionDays = value; }
    public String getPropertiesJson() { return propertiesJson; }
    public void setPropertiesJson(String value) { propertiesJson = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
}
