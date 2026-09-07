package com.aetheris.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aetheris.auth.handler.JsonbJacksonTypeHandler;
import com.aetheris.auth.model.DataScope;

import java.time.OffsetDateTime;
import java.util.List;

@TableName(value = "resource_grant", schema = "auth", autoResultMap = true)
public class ResourceGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String granteeType;
    private Long granteeId;
    private String resourceType;
    private Long resourceId;
    @TableField(typeHandler = JsonbJacksonTypeHandler.class)
    private DataScope dataScope;
    @TableField(typeHandler = JsonbJacksonTypeHandler.class)
    private List<String> actions;
    private Boolean enabled;
    private String grantedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGranteeType() { return granteeType; }
    public void setGranteeType(String granteeType) { this.granteeType = granteeType; }
    public Long getGranteeId() { return granteeId; }
    public void setGranteeId(Long granteeId) { this.granteeId = granteeId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
    public DataScope getDataScope() { return dataScope; }
    public void setDataScope(DataScope dataScope) { this.dataScope = dataScope; }
    public List<String> getActions() { return actions; }
    public void setActions(List<String> actions) { this.actions = actions; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
