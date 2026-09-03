package com.aetheris.auth.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 权限审计事件：授权、撤销、拒绝、越权尝试与权限服务不可用，全部进入
 * system 服务的 permission_event 表（由 system-service 统一落库与查询）。
 *
 * <p>只包含主体与决策要素，绝不携带密码、JWT、数据内容等敏感信息。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionEventRequest {

    private String username;
    private Long userId;
    private String resourceType;
    private String resourceId;
    private String action;
    /** ALLOWED / DENIED / GRANTED / REVOKED / UPDATED / DELETED / UNAVAILABLE */
    private String decision;
    private String reason;
    private String requestId;

    public PermissionEventRequest() {
    }

    public PermissionEventRequest(String username, Long userId, String resourceType,
                                  String resourceId, String action, String decision,
                                  String reason, String requestId) {
        this.username = username;
        this.userId = userId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.action = action;
        this.decision = decision;
        this.reason = reason;
        this.requestId = requestId;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}