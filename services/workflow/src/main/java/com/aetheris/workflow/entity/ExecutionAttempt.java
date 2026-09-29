package com.aetheris.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** One immutable-definition execution attempt for a logical task instance. */
@TableName(value = "execution_attempt", schema = "workflow")
public class ExecutionAttempt {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long instanceId;
    private Integer attemptNo;
    private String status;
    private Long fencingToken;
    private String workerId;
    private String triggerType;
    private String triggeredBy;
    private String taskName;
    private String taskType;
    private Integer taskDefinitionVersion;
    private String taskSql;
    private Long datasourceId;
    private Long jobDefinitionId;
    private Integer jobVersion;
    private Long qualityRuleId;
    private String eventTimeColumn;
    private String watermarkExpression;
    private Integer allowedLatenessSeconds;
    private String lateDataPolicy;
    private String deadLetterTable;
    private OffsetDateTime taskUpdatedAt;
    private String definitionDigest;
    private String parametersJson;
    private String parameterDigest;
    private String artifactDigest;
    private Long sourceRepositoryId;
    private String sourceCommitSha;
    private String sourcePath;
    private String sourceBlobId;
    private String sourceBlobSha256;
    private String sourceSignatureStatus;
    private String sourceSignerFingerprint;
    private OffsetDateTime sourceVerifiedAt;
    private String remoteHandle;
    private String applicationId;
    private String flinkJobId;
    private String flinkLifecycleState;
    private String flinkLifecycleIntent;
    private String flinkUpgradeTaskSql;
    private String flinkUpgradeDefinitionDigest;
    private Long flinkLatestCheckpointId;
    private String flinkSavepointPath;
    private String flinkSavepointRequestId;
    private OffsetDateTime flinkLifecycleUpdatedAt;
    private String inputPartitionsJson;
    private String outputPartitionsJson;
    private String resultMetadataJson;
    private Long logCursor;
    private OffsetDateTime cancellationConfirmedAt;
    private OffsetDateTime queuedAt;
    private OffsetDateTime submittedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime heartbeatAt;
    private OffsetDateTime leaseExpiresAt;
    private OffsetDateTime finishedAt;
    private Long durationMs;
    private Long affectedRows;
    private String errorCode;
    private String errorMessage;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getFencingToken() { return fencingToken; }
    public void setFencingToken(Long fencingToken) { this.fencingToken = fencingToken; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public Integer getTaskDefinitionVersion() { return taskDefinitionVersion; }
    public void setTaskDefinitionVersion(Integer value) { taskDefinitionVersion = value; }
    public String getTaskSql() { return taskSql; }
    public void setTaskSql(String taskSql) { this.taskSql = taskSql; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public Long getJobDefinitionId() { return jobDefinitionId; }
    public void setJobDefinitionId(Long jobDefinitionId) { this.jobDefinitionId = jobDefinitionId; }
    public Integer getJobVersion() { return jobVersion; }
    public void setJobVersion(Integer jobVersion) { this.jobVersion = jobVersion; }
    public Long getQualityRuleId() { return qualityRuleId; }
    public void setQualityRuleId(Long qualityRuleId) { this.qualityRuleId = qualityRuleId; }
    public String getEventTimeColumn() { return eventTimeColumn; }
    public void setEventTimeColumn(String value) { eventTimeColumn = value; }
    public String getWatermarkExpression() { return watermarkExpression; }
    public void setWatermarkExpression(String value) { watermarkExpression = value; }
    public Integer getAllowedLatenessSeconds() { return allowedLatenessSeconds; }
    public void setAllowedLatenessSeconds(Integer value) { allowedLatenessSeconds = value; }
    public String getLateDataPolicy() { return lateDataPolicy; }
    public void setLateDataPolicy(String value) { lateDataPolicy = value; }
    public String getDeadLetterTable() { return deadLetterTable; }
    public void setDeadLetterTable(String value) { deadLetterTable = value; }
    public OffsetDateTime getTaskUpdatedAt() { return taskUpdatedAt; }
    public void setTaskUpdatedAt(OffsetDateTime taskUpdatedAt) { this.taskUpdatedAt = taskUpdatedAt; }
    public String getDefinitionDigest() { return definitionDigest; }
    public void setDefinitionDigest(String definitionDigest) { this.definitionDigest = definitionDigest; }
    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }
    public String getParameterDigest() { return parameterDigest; }
    public void setParameterDigest(String parameterDigest) { this.parameterDigest = parameterDigest; }
    public String getArtifactDigest() { return artifactDigest; }
    public void setArtifactDigest(String artifactDigest) { this.artifactDigest = artifactDigest; }
    public Long getSourceRepositoryId() { return sourceRepositoryId; }
    public void setSourceRepositoryId(Long sourceRepositoryId) { this.sourceRepositoryId = sourceRepositoryId; }
    public String getSourceCommitSha() { return sourceCommitSha; }
    public void setSourceCommitSha(String sourceCommitSha) { this.sourceCommitSha = sourceCommitSha; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public String getSourceBlobId() { return sourceBlobId; }
    public void setSourceBlobId(String sourceBlobId) { this.sourceBlobId = sourceBlobId; }
    public String getSourceBlobSha256() { return sourceBlobSha256; }
    public void setSourceBlobSha256(String sourceBlobSha256) { this.sourceBlobSha256 = sourceBlobSha256; }
    public String getSourceSignatureStatus() { return sourceSignatureStatus; }
    public void setSourceSignatureStatus(String sourceSignatureStatus) { this.sourceSignatureStatus = sourceSignatureStatus; }
    public String getSourceSignerFingerprint() { return sourceSignerFingerprint; }
    public void setSourceSignerFingerprint(String sourceSignerFingerprint) { this.sourceSignerFingerprint = sourceSignerFingerprint; }
    public OffsetDateTime getSourceVerifiedAt() { return sourceVerifiedAt; }
    public void setSourceVerifiedAt(OffsetDateTime sourceVerifiedAt) { this.sourceVerifiedAt = sourceVerifiedAt; }
    public String getRemoteHandle() { return remoteHandle; }
    public void setRemoteHandle(String remoteHandle) { this.remoteHandle = remoteHandle; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getFlinkJobId() { return flinkJobId; }
    public void setFlinkJobId(String value) { flinkJobId = value; }
    public String getFlinkLifecycleState() { return flinkLifecycleState; }
    public void setFlinkLifecycleState(String value) { flinkLifecycleState = value; }
    public String getFlinkLifecycleIntent() { return flinkLifecycleIntent; }
    public void setFlinkLifecycleIntent(String value) { flinkLifecycleIntent = value; }
    public String getFlinkUpgradeTaskSql() { return flinkUpgradeTaskSql; }
    public void setFlinkUpgradeTaskSql(String value) { flinkUpgradeTaskSql = value; }
    public String getFlinkUpgradeDefinitionDigest() { return flinkUpgradeDefinitionDigest; }
    public void setFlinkUpgradeDefinitionDigest(String value) { flinkUpgradeDefinitionDigest = value; }
    public Long getFlinkLatestCheckpointId() { return flinkLatestCheckpointId; }
    public void setFlinkLatestCheckpointId(Long value) { flinkLatestCheckpointId = value; }
    public String getFlinkSavepointPath() { return flinkSavepointPath; }
    public void setFlinkSavepointPath(String value) { flinkSavepointPath = value; }
    public String getFlinkSavepointRequestId() { return flinkSavepointRequestId; }
    public void setFlinkSavepointRequestId(String value) { flinkSavepointRequestId = value; }
    public OffsetDateTime getFlinkLifecycleUpdatedAt() { return flinkLifecycleUpdatedAt; }
    public void setFlinkLifecycleUpdatedAt(OffsetDateTime value) { flinkLifecycleUpdatedAt = value; }
    public String getInputPartitionsJson() { return inputPartitionsJson; }
    public void setInputPartitionsJson(String inputPartitionsJson) { this.inputPartitionsJson = inputPartitionsJson; }
    public String getOutputPartitionsJson() { return outputPartitionsJson; }
    public void setOutputPartitionsJson(String outputPartitionsJson) { this.outputPartitionsJson = outputPartitionsJson; }
    public String getResultMetadataJson() { return resultMetadataJson; }
    public void setResultMetadataJson(String resultMetadataJson) { this.resultMetadataJson = resultMetadataJson; }
    public Long getLogCursor() { return logCursor; }
    public void setLogCursor(Long logCursor) { this.logCursor = logCursor; }
    public OffsetDateTime getCancellationConfirmedAt() { return cancellationConfirmedAt; }
    public void setCancellationConfirmedAt(OffsetDateTime cancellationConfirmedAt) { this.cancellationConfirmedAt = cancellationConfirmedAt; }
    public OffsetDateTime getQueuedAt() { return queuedAt; }
    public void setQueuedAt(OffsetDateTime queuedAt) { this.queuedAt = queuedAt; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(OffsetDateTime heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public OffsetDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(OffsetDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Long getAffectedRows() { return affectedRows; }
    public void setAffectedRows(Long affectedRows) { this.affectedRows = affectedRows; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
