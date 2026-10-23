package com.aetheris.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** 即席查询历史；ownerUid 是归属依据，username 仅为显示快照。 */
@TableName(value = "adhoc_query_history", schema = "metadata")
public class QueryHistory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private Long ownerUid;
    private String requestId;
    private String errorCode;
    private Long datasourceId;
    /** 数据源名称快照：数据源被删除后历史仍可读。 */
    private String datasourceName;
    private String sqlText;
    /** success / failed。 */
    private String status;
    private Long rowCount;
    private Long durationMs;
    private String errorMessage;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Long getOwnerUid() { return ownerUid; }
    public void setOwnerUid(Long ownerUid) { this.ownerUid = ownerUid; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
