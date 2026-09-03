package com.aetheris.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;

/** 可复用的语义维度，绑定到受管元数据表的一个真实字段。 */
@TableName(value = "semantic_dimension", schema = "metadata")
public class SemanticDimension {
    @TableId(type = IdType.AUTO)
    private Long id;
    @NotBlank
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,63}", message = "维度编码仅支持字母、数字和下划线，且必须以字母开头")
    private String dimensionCode;
    @NotBlank private String dimensionName;
    private Long businessDomainId;
    @NotNull private Long metadataTableId;
    @NotBlank private String columnName;
    private String dimensionType;
    private String status;
    private Integer version;
    private Integer publishedVersion;
    private String owner;
    private String description;
    private String createdBy;
    private String publishedBy;
    private OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDimensionCode() { return dimensionCode; }
    public void setDimensionCode(String dimensionCode) { this.dimensionCode = dimensionCode; }
    public String getDimensionName() { return dimensionName; }
    public void setDimensionName(String dimensionName) { this.dimensionName = dimensionName; }
    public Long getBusinessDomainId() { return businessDomainId; }
    public void setBusinessDomainId(Long businessDomainId) { this.businessDomainId = businessDomainId; }
    public Long getMetadataTableId() { return metadataTableId; }
    public void setMetadataTableId(Long metadataTableId) { this.metadataTableId = metadataTableId; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public String getDimensionType() { return dimensionType; }
    public void setDimensionType(String dimensionType) { this.dimensionType = dimensionType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getPublishedVersion() { return publishedVersion; }
    public void setPublishedVersion(Integer publishedVersion) { this.publishedVersion = publishedVersion; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
