package com.aetheris.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/**
 * 发布记录：数仓产出（表/任务/看板/API/模型）的发布申请与流转轨迹。
 *
 * <p>状态机为 pending → approved → released，pending 可转 rejected，released 可转
 * rolled_back；流转只经 ReleaseRecordController 的动作端点驱动，编辑接口不碰状态字段。
 */
@TableName(value = "release_record", schema = "workflow")
public class ReleaseRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发布编号，如 REL-20260902-001；跨系统引用与审计追溯的锚点，全局唯一。 */
    @NotBlank(message = "发布编号不能为空")
    private String releaseNo;

    @NotBlank(message = "发布标题不能为空")
    private String title;

    /** 发布物类型：table/task/dashboard/api/model。 */
    private String releaseType;

    /** 发布对象名称，如目标表名、看板名。 */
    @NotBlank(message = "发布对象不能为空")
    private String targetName;

    /** 发布版本；为空表示尚未定版。 */
    private String targetVersion;

    /** 变更摘要：给审批人看的本次改动说明。 */
    private String changeSummary;

    /** 当前状态：pending/approved/rejected/released/rolled_back。 */
    private String status;

    /** 申请人。 */
    private String requestedBy;

    /** 审批人。 */
    private String approvedBy;

    /** Optional immutable target id and quality gate selected for this release. */
    private Long targetRefId;
    private String snapshotDigest;
    private String approvedDigest;
    private OffsetDateTime approvedAt;
    private Long qualityRuleId;
    private Long qualityResultId;

    /** 实际发布时间；仅发布过（released 及 rolled_back）才有值。 */
    private OffsetDateTime releasedAt;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReleaseNo() { return releaseNo; }
    public void setReleaseNo(String releaseNo) { this.releaseNo = releaseNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getReleaseType() { return releaseType; }
    public void setReleaseType(String releaseType) { this.releaseType = releaseType; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Long getTargetRefId() { return targetRefId; }
    public void setTargetRefId(Long targetRefId) { this.targetRefId = targetRefId; }
    public String getSnapshotDigest() { return snapshotDigest; }
    public void setSnapshotDigest(String snapshotDigest) { this.snapshotDigest = snapshotDigest; }
    public String getApprovedDigest() { return approvedDigest; }
    public void setApprovedDigest(String approvedDigest) { this.approvedDigest = approvedDigest; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(OffsetDateTime approvedAt) { this.approvedAt = approvedAt; }
    public Long getQualityRuleId() { return qualityRuleId; }
    public void setQualityRuleId(Long qualityRuleId) { this.qualityRuleId = qualityRuleId; }
    public Long getQualityResultId() { return qualityResultId; }
    public void setQualityResultId(Long qualityResultId) { this.qualityResultId = qualityResultId; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
