package com.aetheris.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** 表标签：数据表的业务标注（核心表/敏感/待归档等），(表, 标签) 唯一。 */
@TableName(value = "table_tag", schema = "metadata")
public class TableTag {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long metadataTableId;
    private String tagName;
    private String createdBy;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMetadataTableId() { return metadataTableId; }
    public void setMetadataTableId(Long metadataTableId) { this.metadataTableId = metadataTableId; }
    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
