package com.aetheris.metadata.entity;

import com.aetheris.metadata.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;

/** 指标定义：登记指标编码、口径公式与层级（atomic/derived/composite），编码唯一。 */
@TableName(value = "metric_definition", schema = "metadata", autoResultMap = true)
public class MetricDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;
    @NotBlank
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,63}", message = "指标编码仅支持字母、数字和下划线，且必须以字母开头")
    private String metricCode;
    @NotBlank private String metricName;
    private String businessDomain;
    private Long businessDomainId;
    private Long logicalModelId;
    private Long sourceTableId;
    private String layer;
    private String calibreFormula;
    private String aggregation;
    private String measureColumn;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String filtersJson;
    private String timeColumn;
    private String defaultTimeGrain;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String dimensionIdsJson;
    private String unit;
    private String owner;
    private String status;
    private Integer version;
    private Integer publishedVersion;
    private String description;
    private String createdBy;
    private String publishedBy;
    private OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public String getBusinessDomain() { return businessDomain; }
    public void setBusinessDomain(String businessDomain) { this.businessDomain = businessDomain; }
    public Long getBusinessDomainId() { return businessDomainId; }
    public void setBusinessDomainId(Long businessDomainId) { this.businessDomainId = businessDomainId; }
    public Long getLogicalModelId() { return logicalModelId; }
    public void setLogicalModelId(Long logicalModelId) { this.logicalModelId = logicalModelId; }
    public Long getSourceTableId() { return sourceTableId; }
    public void setSourceTableId(Long sourceTableId) { this.sourceTableId = sourceTableId; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public String getCalibreFormula() { return calibreFormula; }
    public void setCalibreFormula(String calibreFormula) { this.calibreFormula = calibreFormula; }
    public String getAggregation() { return aggregation; }
    public void setAggregation(String aggregation) { this.aggregation = aggregation; }
    public String getMeasureColumn() { return measureColumn; }
    public void setMeasureColumn(String measureColumn) { this.measureColumn = measureColumn; }
    public String getFiltersJson() { return filtersJson; }
    public void setFiltersJson(String filtersJson) { this.filtersJson = filtersJson; }
    public String getTimeColumn() { return timeColumn; }
    public void setTimeColumn(String timeColumn) { this.timeColumn = timeColumn; }
    public String getDefaultTimeGrain() { return defaultTimeGrain; }
    public void setDefaultTimeGrain(String defaultTimeGrain) { this.defaultTimeGrain = defaultTimeGrain; }
    public String getDimensionIdsJson() { return dimensionIdsJson; }
    public void setDimensionIdsJson(String dimensionIdsJson) { this.dimensionIdsJson = dimensionIdsJson; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getPublishedVersion() { return publishedVersion; }
    public void setPublishedVersion(Integer publishedVersion) { this.publishedVersion = publishedVersion; }
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
