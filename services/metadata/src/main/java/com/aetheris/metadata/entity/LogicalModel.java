package com.aetheris.metadata.entity;

import com.aetheris.metadata.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/**
 * 逻辑模型：业务域的实体与关系建模（conceptual/relational/dimensional），编码唯一。
 * entitiesJson 以 JSON 字符串形式存储实体与关系描述，对应 JSONB 列。
 */
@TableName(value = "logical_model", schema = "metadata", autoResultMap = true)
public class LogicalModel {

    @TableId(type = IdType.AUTO)
    private Long id;
    @NotBlank private String modelCode;
    @NotBlank private String modelName;
    private String businessDomain;
    private String modelType;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String entitiesJson;
    private String status;
    private Integer version;
    private String description;
    private String owner;
    private String createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getBusinessDomain() { return businessDomain; }
    public void setBusinessDomain(String businessDomain) { this.businessDomain = businessDomain; }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    public String getEntitiesJson() { return entitiesJson; }
    public void setEntitiesJson(String entitiesJson) { this.entitiesJson = entitiesJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
