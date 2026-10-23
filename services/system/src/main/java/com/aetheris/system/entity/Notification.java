package com.aetheris.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName(value = "notification", schema = "system")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String recipient;
    @com.fasterxml.jackson.annotation.JsonIgnore private String inboxKind;
    @com.fasterxml.jackson.annotation.JsonIgnore private Long recipientUid;
    @com.fasterxml.jackson.annotation.JsonIgnore private java.util.UUID eventId;
    @com.fasterxml.jackson.annotation.JsonIgnore private String eventSha256;
    @com.fasterxml.jackson.annotation.JsonIgnore private Long ackUid;
    @com.fasterxml.jackson.annotation.JsonIgnore private OffsetDateTime ackTime;
    /** 事件来源服务：quality / ingestion / workflow 等。 */
    private String source;
    /** 来源对象 id，如规则 id、任务 id；便于前端跳转。 */
    private Long sourceId;
    /** info / warning / critical。 */
    private String level;
    private String title;
    private String message;
    /** 前端跳转路径，如 /governance/quality。 */
    private String linkPath;
    private Boolean isRead;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getInboxKind(){return inboxKind;}
    public void setInboxKind(String value){inboxKind=value;}
    public Long getRecipientUid(){return recipientUid;}
    public void setRecipientUid(Long value){recipientUid=value;}
    public java.util.UUID getEventId(){return eventId;}
    public void setEventId(java.util.UUID value){eventId=value;}
    public String getEventSha256(){return eventSha256;}
    public void setEventSha256(String value){eventSha256=value;}
    public Long getAckUid(){return ackUid;}
    public void setAckUid(Long value){ackUid=value;}
    public OffsetDateTime getAckTime(){return ackTime;}
    public void setAckTime(OffsetDateTime value){ackTime=value;}
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getLinkPath() { return linkPath; }
    public void setLinkPath(String linkPath) { this.linkPath = linkPath; }
    public Boolean getIsRead() { return isRead; }
    public void setIsRead(Boolean isRead) { this.isRead = isRead; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
