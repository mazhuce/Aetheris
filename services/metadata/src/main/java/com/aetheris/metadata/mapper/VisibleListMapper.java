package com.aetheris.metadata.mapper;

import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.entity.MetricDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.*;

/** Bounded candidate reads, independent of visible-row page size. No caller ctx enters SQL. */
@Component
public class VisibleListMapper {
    public static final int CANDIDATE_LIMIT = 500;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public VisibleListMapper(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public record TableFilters(String keyword, String layer, Long businessDomainId, String domain, boolean unassigned, String source) {
        public TableFilters {
            keyword = normalizeKeyword(keyword);
            if (layer != null && !Set.of("ods","dwd","dws","ads","dim","other").contains(layer)) throw new IllegalArgumentException("分层无效");
            if (businessDomainId != null && businessDomainId <= 0) throw new IllegalArgumentException("业务域无效");
            if (domain != null && (domain.isBlank() || domain.length() > 128 || unassigned)) throw new IllegalArgumentException("业务域筛选无效");
            if (source != null && !Set.of("auto","manual").contains(source)) throw new IllegalArgumentException("来源无效");
        }
    }
    public record DomainContext(Map<String,String> ctx, String name) { }
    public record MetricCandidate(MetricDefinition aggregate, SemanticVersion version) { }
    public static String normalizeKeyword(String keyword) {
        if (keyword == null) return "";
        String value = keyword.strip();
        if (value.length() > 128) throw new IllegalArgumentException("关键词不能超过 128 字符");
        return value;
    }
    private static String like(String value) { return "%" + value.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"; }
    public long upper(boolean metric) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM metadata." + (metric ? "metric_definition" : "metadata_table"), Long.class);
        return value == null ? 0 : value;
    }
    public List<MetadataTable> tables(long upper, Long before, TableFilters filters) {
        return tables(upper,before,filters,false,CANDIDATE_LIMIT);
    }
    /** Search's stale exclusion is server-owned and cannot be overridden by query parameters. */
    public List<MetadataTable> searchTables(long upper,Long before,TableFilters filters,int fetchLimit) {
        if(fetchLimit<1 || fetchLimit>CANDIDATE_LIMIT+1) throw new IllegalArgumentException("搜索候选批次无效");
        return tables(upper,before,filters,true,fetchLimit);
    }
    public List<MetadataTable> compatibilityTables(Collection<Long> domainIds,int fetchLimit) {
        if(fetchLimit<1 || fetchLimit>100001) throw new IllegalArgumentException("兼容列表候选批次无效");
        if(domainIds!=null && domainIds.isEmpty()) return List.of();
        if(domainIds==null) return compatibilityWindow(null,fetchLimit);
        // Auth's IDS response can contain 100000 domains: bound placeholders without truncating that set.
        var domains=List.copyOf(new LinkedHashSet<>(domainIds));
        List<MetadataTable> result=new ArrayList<>();
        for(int offset=0;offset<domains.size() && result.size()<fetchLimit;offset+=CANDIDATE_LIMIT) {
            result.addAll(compatibilityWindow(domains.subList(offset,Math.min(domains.size(),offset+CANDIDATE_LIMIT)),fetchLimit-result.size()));
        }
        result.sort(Comparator.comparing(MetadataTable::getId).reversed());
        return List.copyOf(result);
    }
    private List<MetadataTable> compatibilityWindow(Collection<Long> domainIds,int fetchLimit) {
        String sql="SELECT * FROM metadata.metadata_table";
        List<Object> args=new ArrayList<>();
        if(domainIds!=null) {
            sql+=" WHERE business_domain_id IN ("+String.join(",",Collections.nCopies(domainIds.size(),"?"))+")";
            args.addAll(domainIds);
        }
        sql+=" ORDER BY id DESC LIMIT ?";args.add(fetchLimit);
        return jdbc.query(sql,new BeanPropertyRowMapper<>(MetadataTable.class),args.toArray());
    }
    private List<MetadataTable> tables(long upper,Long before,TableFilters filters,boolean excludeStale,int fetchLimit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM metadata.metadata_table WHERE id<=?");
        List<Object> args = new ArrayList<>(); args.add(upper);
        if(excludeStale) sql.append(" AND status<>'stale'");
        if (before != null) { sql.append(" AND id<?"); args.add(before); }
        if (!filters.keyword().isEmpty()) {
            sql.append(" AND (LOWER(table_name) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(table_comment,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(domain,'')) LIKE ? ESCAPE '\\' OR LOWER(datasource_name) LIKE ? ESCAPE '\\')");
            for (int i=0;i<4;i++) args.add(like(filters.keyword()));
        }
        if (filters.layer()!=null) { sql.append(" AND CASE WHEN LOWER(layer) IN ('ods','dwd','dws','ads','dim') THEN LOWER(layer) ELSE 'other' END=?"); args.add(filters.layer()); }
        if (filters.businessDomainId()!=null) { sql.append(" AND business_domain_id=?"); args.add(filters.businessDomainId()); }
        if (filters.domain()!=null) { sql.append(" AND domain=?"); args.add(filters.domain()); }
        if (filters.unassigned()) sql.append(" AND (domain IS NULL OR TRIM(domain)='')");
        if (filters.source()!=null) { sql.append(" AND COALESCE(source,'manual')=?"); args.add(filters.source()); }
        sql.append(" ORDER BY id DESC LIMIT ?"); args.add(fetchLimit);
        return jdbc.query(sql.toString(), new BeanPropertyRowMapper<>(MetadataTable.class), args.toArray());
    }
    public List<MetricCandidate> metrics(long upper, Long before, String status, boolean published) {
        if (status != null && !Set.of("draft","published","deprecated").contains(status)) throw new IllegalArgumentException("指标状态无效");
        String sql = "SELECT m.*" + (published ? ",v.id snapshot_id,v.version_no snapshot_version,v.snapshot_json snapshot_body,v.published_by snapshot_publisher,v.published_at snapshot_time" : "")
                + " FROM metadata.metric_definition m" + (published ? " LEFT JOIN metadata.metric_definition_version v ON v.metric_definition_id=m.id AND v.version_no=m.published_version" : "")
                + " WHERE m.id<=?" + (before == null ? "" : " AND m.id<?")
                + (published ? " AND m.published_version IS NOT NULL AND m.status<>'deprecated'" : status == null ? "" : " AND m.status=?")
                + " ORDER BY m.id DESC LIMIT ?";
        List<Object> args = new ArrayList<>(); args.add(upper); if (before!=null) args.add(before); if (!published && status!=null) args.add(status); args.add(CANDIDATE_LIMIT);
        var mapper = new BeanPropertyRowMapper<>(MetricDefinition.class);
        return jdbc.query(sql, (rs,row)-> {
            MetricDefinition aggregate = mapper.mapRow(rs,row);
            if (!published) return new MetricCandidate(aggregate,null);
            try {
                String raw = rs.getString("snapshot_body"); if (raw==null) throw new IllegalArgumentException();
                var node=json.readTree(raw); var domain=node.get("businessDomainId");
                if (!node.isObject() || domain!=null && !domain.isNull() && (!domain.isIntegralNumber() || !domain.canConvertToLong() || domain.longValue()<0)) throw new IllegalArgumentException();
                return new MetricCandidate(aggregate,new SemanticVersion(rs.getLong("snapshot_id"),rs.getInt("snapshot_version"),node,rs.getString("snapshot_publisher"),rs.getObject("snapshot_time",OffsetDateTime.class)));
            } catch (Exception error) { throw new InvalidSnapshotException(); }
        },args.toArray());
    }
    public Map<Long,DomainContext> domains(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size()>CANDIDATE_LIMIT) throw new IllegalArgumentException("域批次过大");
        String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        Map<Long,DomainContext> result=new HashMap<>();
        jdbc.query("SELECT id,domain_code,domain_name FROM metadata.business_domain WHERE id IN ("+marks+")", rs -> {
            long id=rs.getLong("id");String code=rs.getString("domain_code");
            result.put(id,new DomainContext(code==null || code.isBlank()?Map.of("domainId",Long.toString(id)):Map.of("domainId",Long.toString(id),"domainCode",code),rs.getString("domain_name")));
        },ids.toArray());
        return result;
    }
    public static class InvalidSnapshotException extends RuntimeException { public InvalidSnapshotException() { super("指标发布快照无效"); } }
}
