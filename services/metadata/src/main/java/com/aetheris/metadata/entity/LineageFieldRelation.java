package com.aetheris.metadata.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
@TableName(value="lineage_field_relation", schema="metadata")
public class LineageFieldRelation {
    @TableId(type=IdType.AUTO) private Long id; private String sourceTable; private String sourceColumn; private String targetTable; private String targetColumn; private String relationType; private String expression; private String source; private BigDecimal confidence; private String description; private String createdBy; private OffsetDateTime createdAt; private OffsetDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;} public String getSourceTable(){return sourceTable;} public void setSourceTable(String v){sourceTable=v;} public String getSourceColumn(){return sourceColumn;} public void setSourceColumn(String v){sourceColumn=v;} public String getTargetTable(){return targetTable;} public void setTargetTable(String v){targetTable=v;} public String getTargetColumn(){return targetColumn;} public void setTargetColumn(String v){targetColumn=v;} public String getRelationType(){return relationType;} public void setRelationType(String v){relationType=v;} public String getExpression(){return expression;} public void setExpression(String v){expression=v;} public String getSource(){return source;} public void setSource(String v){source=v;} public BigDecimal getConfidence(){return confidence;} public void setConfidence(BigDecimal v){confidence=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;} public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;} public OffsetDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(OffsetDateTime v){createdAt=v;} public OffsetDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(OffsetDateTime v){updatedAt=v;}
}
