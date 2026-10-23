package com.aetheris.metadata.service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class QueryAuditService {
    static final long DEFAULT_SLOW_QUERY_MS = 3000L;
    private final JdbcTemplate jdbc;
    private final Counter queryCount;
    private final Counter failedQueryCount;
    private final Counter slowQueryCount;
    private final Counter persistenceFailures;
    private final Timer queryDuration;

    public QueryAuditService(JdbcTemplate jdbc){
        this(jdbc, new SimpleMeterRegistry());
    }

    @Autowired
    public QueryAuditService(JdbcTemplate jdbc, MeterRegistry meterRegistry){
        this.jdbc=jdbc;
        this.queryCount=Counter.builder("aetheris.query.audit.count")
                .description("Completed semantic and ad-hoc queries recorded by the audit service")
                .register(meterRegistry);
        this.failedQueryCount=Counter.builder("aetheris.query.audit.failed")
                .description("Queries recorded with a non-success status")
                .register(meterRegistry);
        this.slowQueryCount=Counter.builder("aetheris.query.audit.slow")
                .description("Queries whose recorded duration reaches the default slow-query threshold")
                .register(meterRegistry);
        this.persistenceFailures=Counter.builder("aetheris.query.audit.persist.failed")
                .description("Failed query audit inserts; inspect the audit database before accepting an audit gap")
                .register(meterRegistry);
        this.queryDuration=Timer.builder("aetheris.query.audit.duration")
                .description("Recorded query duration")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry);
    }

    public void record(String kind,String assetCode,Long assetId,Long datasourceId,String user,String status,Long rows,Long duration,Boolean truncated,String errorCode,String error,String query){
        VerifiedActor actor=verifiedActor(user);
        String code="success".equalsIgnoreCase(status)?null:safeErrorCode(errorCode);
        update("INSERT INTO metadata.semantic_query_audit (query_kind,asset_code,asset_id,datasource_id,username,status,row_count,duration_ms,truncated,error_code,error_message,query_hash,request_id,actor_uid) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",kind,assetCode,assetId,datasourceId,user,status,rows,duration,truncated==null?false:truncated,code,safeErrorMessage(code),hash(query),actor.requestId(),actor.uid());
        queryCount.increment();
        if (!"success".equalsIgnoreCase(status)) {
            failedQueryCount.increment();
        }
        if (duration != null && duration >= DEFAULT_SLOW_QUERY_MS) {
            slowQueryCount.increment();
        }
        if (duration != null && duration >= 0) {
            queryDuration.record(duration, TimeUnit.MILLISECONDS);
        }
    }
    public Map<String,Object> summary(long actorUid, boolean global, int days, Long slowMs){
        if(actorUid<=0)throw new IllegalArgumentException("Stable audit user required");
        int window=Math.clamp(days,1,90); long threshold=slowMs==null?3000:Math.clamp(slowMs,100,600000);
        String filter=global?"created_at >= now() - (? * interval '1 day')":"created_at >= now() - (? * interval '1 day') AND actor_uid = ?";
        List<Object> args=global?List.of(window):List.of(window,actorUid);
        String sql="SELECT count(*) AS total, count(*) FILTER (WHERE status='success') AS success, count(*) FILTER (WHERE status<>'success') AS failed, coalesce(avg(duration_ms),0) AS avg_duration, coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms),0) AS p50, coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms),0) AS p95, count(*) FILTER (WHERE duration_ms >= ?) AS slow FROM metadata.semantic_query_audit WHERE "+filter;
        java.util.ArrayList<Object> params=new java.util.ArrayList<>(); params.add(threshold); params.addAll(args);
        Map<String,Object> row=jdbc.queryForMap(sql,params.toArray()); Number total=(Number)row.get("total"), success=(Number)row.get("success"); double rate=total==null||total.longValue()==0?1.0:success.doubleValue()/total.doubleValue(); return Map.of("total",total==null?0:total.longValue(),"success",success==null?0:success.longValue(),"failed",((Number)row.getOrDefault("failed",0)).longValue(),"successRate",rate,"avgDurationMs",((Number)row.getOrDefault("avg_duration",0)).doubleValue(),"p50DurationMs",((Number)row.getOrDefault("p50",0)).doubleValue(),"p95DurationMs",((Number)row.getOrDefault("p95",0)).doubleValue(),"slowQueries",((Number)row.getOrDefault("slow",0)).longValue(),"days",window,"slowThresholdMs",threshold);
    }
    public void recordPublished(String kind,String assetCode,Long assetId,Long datasourceId,String user,String status,Long rows,Long duration,
                                String query,long keyId,String admissionId,String requestId,long ownerUid){
        update("INSERT INTO metadata.semantic_query_audit (query_kind,asset_code,asset_id,datasource_id,username,status,row_count,duration_ms,truncated,error_code,query_hash,api_key_id,admission_id,request_id,owner_uid,actor_uid) VALUES (?,?,?,?,?,?,?,?,false,?,?,?,?::uuid,?,?,?)",
                kind,assetCode,assetId,datasourceId,user,status,rows,duration,"success".equals(status)?null:"PUBLISHED_QUERY_FAILED",hash(query),keyId,admissionId,requestId,ownerUid,ownerUid);
        queryCount.increment();if(!"success".equals(status))failedQueryCount.increment();
        if(duration!=null&&duration>=DEFAULT_SLOW_QUERY_MS)slowQueryCount.increment();if(duration!=null&&duration>=0)queryDuration.record(duration,TimeUnit.MILLISECONDS);
    }
    private void update(String sql,Object... values) {
        try { jdbc.update(sql,values); }
        catch (RuntimeException failure) { persistenceFailures.increment(); throw failure; }
    }
    private record VerifiedActor(long uid,String requestId) { }
    private static VerifiedActor verifiedActor(String username) {
        if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes))
            throw new IllegalStateException("Trusted query audit actor required");
        if (!(attributes.getRequest().getAttribute(com.aetheris.security.servlet.TrustedUser.ATTRIBUTE)
                instanceof com.aetheris.security.servlet.TrustedUser user)
                || !user.proof().username().equals(username))
            throw new IllegalStateException("Trusted query audit actor required");
        return new VerifiedActor(user.proof().uid(),user.proof().requestId());
    }
    private static String safeErrorCode(String input) {
        if (input == null) return "QUERY_FAILED";
        return switch (input) {
            case "TIMEOUT", "QUERY_FAILED", "QUERY_UNAVAILABLE", "SQL_POLICY_REJECTED", "PUBLISHED_QUERY_FAILED" -> input;
            case "IllegalArgumentException" -> "INVALID_QUERY";
            case "IllegalStateException" -> "QUERY_UNAVAILABLE";
            default -> "QUERY_FAILED";
        };
    }
    private static String safeErrorMessage(String code) {
        if (code == null) return null;
        return "TIMEOUT".equals(code)?"Query timed out; correlate with requestId":"Query failed; correlate with requestId";
    }
    private String hash(String value){if(value==null)return null;try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){return null;}}
}
