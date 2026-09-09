package com.aetheris.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/** 任务定义：描述「怎么跑」，与运行实例分离，修改定义不影响历史实例。 */
@TableName(value = "task", schema = "workflow")
public class Task {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message = "任务名称不能为空")
    private String name;

    /** sql / spark / quality; concrete fields are validated by task type. */
    private String taskType;

    private Long datasourceId;

    private String datasourceName;

    private String taskSql;

    /** Published managed Spark job definition for spark tasks. */
    private Long jobDefinitionId;

    /** Quality-service rule id for quality gate tasks. */
    private Long qualityRuleId;

    /** Quartz cron 表达式；为空表示仅手动触发。 */
    private String cronExpression;

    private String targetTable;

    @Min(value = 1, message = "超时时间至少 1 秒")
    @Max(value = 86400, message = "超时时间最多 86400 秒")
    private Integer timeoutSeconds;

    @Min(value = 0, message = "重试次数不能为负")
    @Max(value = 10, message = "重试次数最多 10 次")
    private Integer maxRetries;

    private String status;
    private String owner;
    private String description;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public Long getDatasourceId() { return datasourceId; }
    public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getTaskSql() { return taskSql; }
    public void setTaskSql(String taskSql) { this.taskSql = taskSql; }
    public Long getJobDefinitionId() { return jobDefinitionId; }
    public void setJobDefinitionId(Long jobDefinitionId) { this.jobDefinitionId = jobDefinitionId; }
    public Long getQualityRuleId() { return qualityRuleId; }
    public void setQualityRuleId(Long qualityRuleId) { this.qualityRuleId = qualityRuleId; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
