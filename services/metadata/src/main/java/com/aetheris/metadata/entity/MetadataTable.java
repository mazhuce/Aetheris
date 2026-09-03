package com.aetheris.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

@TableName(value = "metadata_table", schema = "metadata")
public class MetadataTable {
    @TableId(type = IdType.AUTO)
    private Long id;
    @NotBlank private String datasourceName;
    private String schemaName;
    @NotBlank private String tableName;
    private String tableComment;
    private String owner;
    private Long rowCount;
    private String status;
    private Long datasourceId;
    private String tableType;
    private Integer columnCount;
    /** manual=手工登记，auto=自动采集；采集只覆盖 auto 记录。 */
    private String source;
    /** 数仓层级：ods/dwd/dws/ads/dim/other，采集时按库名推断。 */
    private String layer;
    /** 业务域，例如 electronic_commerce；当前由人工维护。 */
    private String domain;
    /** 正式业务域外键；domain 文本仅作为历史兼容展示。 */
    private Long businessDomainId;
    /** layer 的来源：auto=按库名推断，manual=人工指定；人工指定后采集不覆盖。 */
    private String layerSource;
    private OffsetDateTime syncedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getTableComment() { return tableComment; }
    public void setTableComment(String tableComment) { this.tableComment = tableComment; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getTableType() { return tableType; }
    public void setTableType(String tableType) { this.tableType = tableType; }
    public Integer getColumnCount() { return columnCount; }
    public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public Long getBusinessDomainId() { return businessDomainId; }
    public void setBusinessDomainId(Long businessDomainId) { this.businessDomainId = businessDomainId; }
    public String getLayerSource() { return layerSource; }
    public void setLayerSource(String layerSource) { this.layerSource = layerSource; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
