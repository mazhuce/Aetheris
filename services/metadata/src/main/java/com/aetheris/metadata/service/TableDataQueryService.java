package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.*;
import com.aetheris.metadata.entity.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Compiles only a persisted table and known column names; authorization is the caller's prior responsibility. */
@Service
public final class TableDataQueryService {
    private final DataSourceService sources;private final JdbcService jdbc;
    public TableDataQueryService(DataSourceService sources,JdbcService jdbc){this.sources=sources;this.jdbc=jdbc;}
    public record Plan(DataSource source,String sql){}
    public Plan prepare(MetadataTable table,TableQueryRequest request){
        var source=table.getDatasourceId()==null?null:sources.getById(table.getDatasourceId());if(source==null)throw new IllegalArgumentException("表所属数据源不可用");
        var actual=request==null?new TableQueryRequest(List.of(),100):request;
        var known=jdbc.listColumns(source,table.getSchemaName(),table.getTableName());var columns=new HashMap<String,String>();
        for(var column:known)columns.put(column.name().toLowerCase(Locale.ROOT),column.name());
        List<String> selected;
        if(actual.columns().isEmpty())selected=known.stream().limit(50).map(DataSourceColumnDto::name).toList();
        else{if(actual.columns().size()>50)throw new IllegalArgumentException("单次最多选择 50 个字段");var result=new ArrayList<String>();var seen=new HashSet<String>();
            for(var value:actual.columns()){if(value==null||!value.matches("[A-Za-z_][A-Za-z0-9_$]*"))throw new IllegalArgumentException("字段名包含不支持的字符");
                var resolved=columns.get(value.toLowerCase(Locale.ROOT));if(resolved==null||!seen.add(resolved.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("字段不存在或重复："+value);result.add(resolved);}selected=result;}
        if(selected.isEmpty())throw new IllegalArgumentException("表没有可查询字段");
        String projection=selected.stream().map(v->quote(v,source)).reduce((a,b)->a+", "+b).orElseThrow();
        String name=(table.getSchemaName()==null||table.getSchemaName().isBlank()?"":quote(table.getSchemaName(),source)+".")+quote(table.getTableName(),source);
        return new Plan(source,"SELECT "+projection+" FROM "+name+" LIMIT "+Math.clamp(actual.limit()==null?100:actual.limit(),1,100));
    }
    public Map<String,Object> execute(Plan plan){return jdbc.query(plan.source(),plan.sql());}
    private static String quote(String value,DataSource source){
        if(value==null||!value.matches("[A-Za-z_][A-Za-z0-9_$]*"))throw new IllegalArgumentException("表或字段名包含不支持的字符");
        var type=source.getType()==null?"":source.getType().toLowerCase(Locale.ROOT);var marker=type.contains("postgres")||type.contains("greenplum")||type.contains("opengauss")?"\"":"`";return marker+value+marker;
    }
}
