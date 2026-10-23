package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.*;
import com.aetheris.metadata.entity.*;
import com.aetheris.metadata.mapper.VisibleListMapper;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.security.servlet.TrustedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.*;

/** Bounded scanning for these two metadata resources, never a permission snapshot/cache. */
@Component
public class VisibleListPager {
    private final VisibleListMapper mapper;
    private final MetricDefinitionService metrics;
    private final MetadataSecurity security;
    private final ObjectMapper json;
    public VisibleListPager(VisibleListMapper mapper, MetricDefinitionService metrics, MetadataSecurity security, ObjectMapper json) {
        this.mapper=mapper;this.metrics=metrics;this.security=security;this.json=json;
    }
    public VisibleListPage<MetadataTable> tables(HttpServletRequest request, VisibleListMapper.TableFilters filters, int size, String token, boolean catalog) {
        var scope=security.visibilityStrict(request,"table","view");
        var cursor=cursor(request,catalog?"table-catalog":"table",filters,size,token,false);
        var rows=mapper.tables(cursor.upper(),cursor.before(),filters);
        return visible(request,"table",scope,cursor,rows,MetadataTable::getId,MetadataTable::getBusinessDomainId, value->true);
    }
    public List<MetadataTable> completeTables(HttpServletRequest request,int candidateBudget,int rowBudget) {
        validateBudgets(candidateBudget,rowBudget);
        var scope=security.visibilityStrict(request,"table","view");
        int budget=scope.mode()==MetadataSecurity.ScopeMode.OPAQUE?candidateBudget:Math.min(candidateBudget,rowBudget);
        var rows=mapper.compatibilityTables(scope.mode()==MetadataSecurity.ScopeMode.IDS?scope.ids():null,budget+1);
        if(rows.size()>budget) throw new ListLimitException();
        List<MetadataTable> result=new ArrayList<>();
        for(int offset=0;offset<rows.size();offset+=VisibleListMapper.CANDIDATE_LIMIT) {
            var window=rows.subList(offset,Math.min(rows.size(),offset+VisibleListMapper.CANDIDATE_LIMIT));
            var allowed=permitted(request,"table",scope,window,MetadataTable::getId,MetadataTable::getBusinessDomainId);
            for(var row:window) if(allowed.contains(row.getId())) {
                result.add(row);if(result.size()>rowBudget) throw new ListLimitException();
            }
        }
        return List.copyOf(result);
    }
    public static void validateBudgets(int candidates,int rows) {
        if(candidates<1 || candidates>100000 || rows<1 || rows>20000)
            throw new IllegalArgumentException("兼容列表预算必须为正数，候选不超过100000，记录不超过20000");
    }
    public List<MetadataTable> search(HttpServletRequest request,String keyword,int limit,int candidateBudget) {
        security.requireIdentity(request);
        if(limit<1 || limit>50) throw new IllegalArgumentException("limit 必须在 1 到 50 之间");
        if(candidateBudget<1 || candidateBudget>5000) throw new IllegalArgumentException("搜索候选预算必须在1到5000之间");
        keyword=VisibleListMapper.normalizeKeyword(keyword);if(keyword.isEmpty()) return List.of();
        var scope=security.visibilityStrict(request,"table","view");
        var filters=new VisibleListMapper.TableFilters(keyword,null,null,null,false,null);
        long upper=mapper.upper(false);Long before=null;
        int remaining=candidateBudget;List<MetadataTable> result=new ArrayList<>();
        while(remaining>0) {
            int budget=Math.min(remaining,VisibleListMapper.CANDIDATE_LIMIT);
            // Only the last budget window takes one lookahead row, which is never authorized or returned.
            int fetch=budget+(remaining<=VisibleListMapper.CANDIDATE_LIMIT?1:0);
            var rows=mapper.searchTables(upper,before,filters,fetch);
            var window=rows.subList(0,Math.min(rows.size(),budget));
            var allowed=permitted(request,"table",scope,window,MetadataTable::getId,MetadataTable::getBusinessDomainId);
            for(var row:window) if(allowed.contains(row.getId())) {
                result.add(row);if(result.size()==limit) return List.copyOf(result);
            }
            if(rows.size()<fetch) return List.copyOf(result);
            remaining-=window.size();
            if(remaining==0) throw new ListLimitException("搜索超过候选扫描上限，请缩小关键词范围");
            before=window.getLast().getId();
        }
        throw new ListLimitException("搜索超过候选扫描上限，请缩小关键词范围");
    }
    public VisibleListPage<MetricDefinition> metrics(HttpServletRequest request,String keyword,String status,int size,String token,boolean published) {
        keyword=VisibleListMapper.normalizeKeyword(keyword);
        var scope=security.visibilityStrict(request,"metric","view");
        var cursor=cursor(request,published?"published-metric":"metric",Arrays.asList(keyword,status),size,token,true);
        var candidates=mapper.metrics(cursor.upper(),cursor.before(),status,published);
        var rows=published?metrics.resolvePublishedBatch(candidates):candidates.stream().map(VisibleListMapper.MetricCandidate::aggregate).toList();
        String key=keyword.toLowerCase(Locale.ROOT);
        return visible(request,"metric",scope,cursor,rows,MetricDefinition::getId,MetricDefinition::getBusinessDomainId,
                metric->key.isEmpty() || java.util.stream.Stream.of(metric.getMetricName(),metric.getMetricCode(),metric.getBusinessDomain()).filter(Objects::nonNull).anyMatch(text->text.toLowerCase(Locale.ROOT).contains(key)));
    }
    private VisibleListCursor cursor(HttpServletRequest request,String kind,Object filters,int size,String token,boolean metric) {
        VisibleListCursor.validateSize(size);
        var user=TrustedUser.require(request).proof();
        try {
            String hash=VisibleListCursor.hash(json.writeValueAsString(Arrays.asList(kind,filters,size,user.uid(),user.username(),user.accessVersion())));
            return VisibleListCursor.decode(token,size,hash,token==null?mapper.upper(metric):0);
        } catch(com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
    }
    private <T> VisibleListPage<T> visible(HttpServletRequest request,String type,MetadataSecurity.Scope scope,VisibleListCursor cursor,List<T> rows,
            Function<T,Long> id,Function<T,Long> domain,Predicate<T> matches) {
        Set<Long> allowed=permitted(request,type,scope,rows.stream().filter(matches).toList(),id,domain);
        List<T> visible=new ArrayList<>();long last=0;int consumed=0;
        for(var row:rows) {
            last=id.apply(row);consumed++;
            if(matches.test(row) && allowed.contains(last)) visible.add(row);
            if(visible.size()==cursor.size()) break;
        }
        boolean more=consumed<rows.size() || rows.size()==VisibleListMapper.CANDIDATE_LIMIT;
        return new VisibleListPage<>(visible,more?cursor.after(last):null,more);
    }
    private <T> Set<Long> permitted(HttpServletRequest request,String type,MetadataSecurity.Scope scope,List<T> rows,
            Function<T,Long> id,Function<T,Long> domain) {
        if(rows.size()>VisibleListMapper.CANDIDATE_LIMIT) throw new IllegalArgumentException("权限候选批次过大");
        if(scope.mode()==MetadataSecurity.ScopeMode.ALL) return rows.stream().map(id).collect(java.util.stream.Collectors.toSet());
        if(scope.mode()==MetadataSecurity.ScopeMode.IDS) {
            var domains=new HashSet<>(scope.ids());
            return rows.stream().filter(row->domain.apply(row)!=null && domains.contains(domain.apply(row)))
                    .map(id).collect(java.util.stream.Collectors.toSet());
        }
        var contexts=mapper.domains(rows.stream().map(domain).filter(Objects::nonNull).distinct().toList());
        Map<Long,Map<String,String>> checks=new LinkedHashMap<>();
        for(var row:rows) {
            Long key=domain.apply(row);var context=key==null?null:contexts.get(key);
            checks.put(id.apply(row),context==null?Map.of():context.ctx());
        }
        return security.allowedIdsStrict(security.requireIdentity(request).username(),type,"view",checks);
    }
    public static class ListLimitException extends RuntimeException {
        public ListLimitException() { this("兼容全量列表超过配置上限，请使用分页接口"); }
        public ListLimitException(String message) { super(message); }
    }
}
