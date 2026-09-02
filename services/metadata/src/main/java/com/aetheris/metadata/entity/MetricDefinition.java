package com.aetheris.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/** 指标定义：登记指标编码、口径公式与层级（atomic/derived/composite），编码唯一。 */
@TableName(value = "metric_definition", schema = "metadata")
public class MetricDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;
    @NotBlank private String metricCode;
    @NotBlank private String metricName;
    private String businessDomain;
    private String layer;
    private String calibreFormula;
    private String unit;
    private String owner;
    private String status;
    private Integer version;
    private String description;
    private String createdBy;
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
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public String getCalibreFormula() { return calibreFormula; }
    public void setCalibreFormula(String calibreFormula) { this.calibreFormula = calibreFormula; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
