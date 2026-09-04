package com.aetheris.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName(value = "semantic_asset_review", schema = "metadata")
public class SemanticAssetReview {
    @TableId(type = IdType.AUTO) private Long id;
    private String assetType;
    private Long assetId;
    private Integer versionNo;
    private String status;
    private String requestedBy;
    private String reviewedBy;
    private String changeNote;
    private String reviewComment;
    private OffsetDateTime publishWindowStart;
    private OffsetDateTime publishWindowEnd;
    private OffsetDateTime requestedAt;
    private OffsetDateTime reviewedAt;
    private OffsetDateTime publishedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getAssetType(){return assetType;} public void setAssetType(String v){assetType=v;}
    public Long getAssetId(){return assetId;} public void setAssetId(Long v){assetId=v;}
    public Integer getVersionNo(){return versionNo;} public void setVersionNo(Integer v){versionNo=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getRequestedBy(){return requestedBy;} public void setRequestedBy(String v){requestedBy=v;}
    public String getReviewedBy(){return reviewedBy;} public void setReviewedBy(String v){reviewedBy=v;}
    public String getChangeNote(){return changeNote;} public void setChangeNote(String v){changeNote=v;}
    public String getReviewComment(){return reviewComment;} public void setReviewComment(String v){reviewComment=v;}
    public OffsetDateTime getPublishWindowStart(){return publishWindowStart;} public void setPublishWindowStart(OffsetDateTime v){publishWindowStart=v;}
    public OffsetDateTime getPublishWindowEnd(){return publishWindowEnd;} public void setPublishWindowEnd(OffsetDateTime v){publishWindowEnd=v;}
    public OffsetDateTime getRequestedAt(){return requestedAt;} public void setRequestedAt(OffsetDateTime v){requestedAt=v;}
    public OffsetDateTime getReviewedAt(){return reviewedAt;} public void setReviewedAt(OffsetDateTime v){reviewedAt=v;}
    public OffsetDateTime getPublishedAt(){return publishedAt;} public void setPublishedAt(OffsetDateTime v){publishedAt=v;}
}
