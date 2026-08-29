package com.aetheris.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** 任务依赖边：task 依赖 upstreamTask，上游成功后下游才可执行。 */
@TableName(value = "task_dependency", schema = "workflow")
public class TaskDependency {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long upstreamTaskId;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getUpstreamTaskId() { return upstreamTaskId; }
    public void setUpstreamTaskId(Long upstreamTaskId) { this.upstreamTaskId = upstreamTaskId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
