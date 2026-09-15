package com.aetheris.metadata.service;
import com.aetheris.metadata.entity.LineageFieldRelation;
import com.aetheris.metadata.mapper.LineageFieldRelationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FieldLineageService {
    private static final Pattern INSERT = Pattern.compile("(?is)INSERT\\s+(?:OVERWRITE|INTO)\\s+TABLE\\s+([\\w.$]+)\\s*(?:\\(([^)]*)\\))?\\s+SELECT\\s+(.*?)\\s+FROM\\s+([\\w.$]+)");
    private static final Pattern CREATE = Pattern.compile("(?is)CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TABLE|VIEW)\\s+([\\w.$]+).*?\\s+AS\\s+SELECT\\s+(.*?)\\s+FROM\\s+([\\w.$]+)");
    private final LineageFieldRelationMapper mapper;
    public FieldLineageService(LineageFieldRelationMapper mapper){this.mapper=mapper;}
    public List<LineageFieldRelation> list(String table, String column){ return mapper.selectList(new LambdaQueryWrapper<LineageFieldRelation>().eq(StringUtils.hasText(table), LineageFieldRelation::getSourceTable, table).eq(StringUtils.hasText(column), LineageFieldRelation::getSourceColumn, column).orderByDesc(LineageFieldRelation::getUpdatedAt)); }
    public LineageFieldRelation save(LineageFieldRelation relation, String user){ relation.setSource("manual"); relation.setCreatedBy(user); relation.setRelationType(StringUtils.hasText(relation.getRelationType())?relation.getRelationType():"etl"); relation.setConfidence(relation.getConfidence()==null?BigDecimal.ONE:relation.getConfidence()); mapper.insert(relation); return relation; }
    public LineageFieldRelation update(Long id, LineageFieldRelation relation){ relation.setId(id); if(mapper.updateById(relation)==0) throw new IllegalArgumentException("字段血缘不存在"); return mapper.selectById(id); }
    public void delete(Long id){ if(mapper.deleteById(id)==0) throw new IllegalArgumentException("字段血缘不存在"); }
    @Transactional public Map<String,Object> parse(String sql, String relationType, String user){
        String clean=sql.replaceAll("(?s)--.*?(?:\\r?\\n|$)"," ").replaceAll("(?s)/\\*.*?\\*/"," "); List<LineageFieldRelation> created=new ArrayList<>();
        Matcher m=INSERT.matcher(clean); while(m.find()) created.addAll(map(m.group(1),m.group(2),m.group(3),m.group(4),relationType,user));
        Matcher c=CREATE.matcher(clean); while(c.find()) created.addAll(map(c.group(1),null,c.group(2),c.group(3),relationType,user));
        return Map.of("parsed",created.size(),"relations",created);
    }
    private List<LineageFieldRelation> map(String target,String targetColumns,String select,String source,String type,String user){
        List<String> targets=split(targetColumns); List<String> expressions=split(select); List<LineageFieldRelation> out=new ArrayList<>();
        for(int i=0;i<expressions.size();i++){ String expr=expressions.get(i).trim(); String targetColumn=i<targets.size()?targets.get(i).trim():alias(expr); String sourceColumn=column(expr); if(targetColumn==null||sourceColumn==null) continue; LineageFieldRelation r=new LineageFieldRelation(); r.setSourceTable(source); r.setSourceColumn(sourceColumn); r.setTargetTable(target); r.setTargetColumn(targetColumn); r.setRelationType(StringUtils.hasText(type)?type.trim():"etl"); r.setExpression(expr); r.setSource("parsed"); r.setConfidence(expr.matches("(?i)[\\w.]+(?:\\s+AS\\s+\\w+)?")?BigDecimal.ONE:new BigDecimal("0.65")); r.setDescription("由 SQL 字段投影解析生成"); r.setCreatedBy(user); mapper.insert(r); out.add(r); }
        return out;
    }
    private List<String> split(String value){ if(!StringUtils.hasText(value)) return List.of(); List<String> out=new ArrayList<>(); int depth=0; StringBuilder b=new StringBuilder(); for(char ch:value.toCharArray()){ if(ch=='(')depth++; if(ch==')')depth--; if(ch==','&&depth==0){out.add(b.toString());b.setLength(0);}else b.append(ch);} if(b.length()>0)out.add(b.toString()); return out; }
    private String column(String expr){ String normalized=expr.trim().replaceAll("(?i)\\s+AS\\s+\\w+\\s*$","").trim(); if(normalized.matches("[\\w]+\\.[\\w]+")) return normalized.substring(normalized.indexOf('.')+1); if(normalized.matches("[\\w]+")) return normalized; Matcher matcher=Pattern.compile("(?:^|[^\\w])(?:[A-Za-z_]\\w*\\.)?([A-Za-z_]\\w*)").matcher(normalized); return matcher.find()?matcher.group(1):null; }
    private String alias(String expr){ Matcher m=Pattern.compile("(?i)\\s+AS\\s+([\\w]+)\\s*$").matcher(expr); return m.find()?m.group(1):column(expr); }
 public List<LineageFieldRelation> impact(String table,String column){ Set<String> seen=new HashSet<>(); List<LineageFieldRelation> result=new ArrayList<>(); Deque<String> queue=new ArrayDeque<>(); queue.add(table+"|"+column); while(!queue.isEmpty()&&seen.size()<500){ String key=queue.removeFirst(); if(!seen.add(key))continue; String[] parts=key.split("\\|",2); List<LineageFieldRelation> next=mapper.selectBySource(parts[0],parts[1]); for(LineageFieldRelation r:next){result.add(r);queue.addLast(r.getTargetTable()+"|"+r.getTargetColumn());}} return result; }
}
