package com.aetheris.ingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName(value = "ingestion_run", schema = "ingestion")
public class IngestionRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String status;
    /** 客户端提供的幂等键；同一任务同一键只允许产生一个运行记录。 */
    private String requestKey;
    /** 重放来源运行记录，便于追踪失败恢复链。 */
    private Long replayOfRunId;
    private Long rowsRead;
    private Long rowsWritten;
    private Long durationMs;
    private String errorMessage;
    private String failureCode;
    private String triggeredBy;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String requestKey) { this.requestKey = requestKey; }
    public Long getReplayOfRunId() { return replayOfRunId; }
    public void setReplayOfRunId(Long replayOfRunId) { this.replayOfRunId = replayOfRunId; }
    public Long getRowsRead() { return rowsRead; }
    public void setRowsRead(Long rowsRead) { this.rowsRead = rowsRead; }
    public Long getRowsWritten() { return rowsWritten; }
    public void setRowsWritten(Long rowsWritten) { this.rowsWritten = rowsWritten; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
