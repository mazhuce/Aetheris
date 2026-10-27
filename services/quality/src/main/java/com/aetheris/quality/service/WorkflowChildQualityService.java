package com.aetheris.quality.service;

import com.aetheris.quality.fixedexecution.*;
import com.aetheris.security.protocol.execution.WorkflowQualityParent;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** A parent reference is resolved before claim; retries/status never dispatch an existing child again. */
@Service
public class WorkflowChildQualityService implements AutoCloseable {
    private final SignedHttpClient workflow;private final QualityExecutionStore store;private final QualityFixedExecutionFacade execution;private final JdbcTemplate jdbc;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),
            Thread.ofVirtual().inheritInheritableThreadLocals(false).name("quality-child-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public WorkflowChildQualityService(@Qualifier("qualityWorkflowTransport") SignedHttpClient workflow,QualityExecutionStore store,QualityFixedExecutionFacade execution,JdbcTemplate jdbc){this.workflow=Objects.requireNonNull(workflow);this.store=Objects.requireNonNull(store);this.execution=Objects.requireNonNull(execution);this.jdbc=Objects.requireNonNull(jdbc);}
    public record Definition(long ruleId,long executionRevision,String definitionSha256,boolean approved,String status){}
    public Definition definition(long id){var d=store.definition(id);return new Definition(id,d.revision(),d.definitionSha256(),d.approved(),d.status());}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Result(long resultId,long ruleId,String manifestSha256,Long metadataReceiptId,WorkflowQualityParent.Reference parent,
                         String state,Long violationCount,long thresholdValue,Long durationMs,String finishedAt){}
    public Result submit(WorkflowQualityParent.Reference ref){
        var parent=resolve(ref);var claim=store.claimChild(parent);
        if(!claim.reused()){
            try{workers.execute(()->{try{execution.execute(claim);}catch(RuntimeException uncertain){/* The facade persists unknown/error; no second dispatch. */}});}
            catch(RejectedExecutionException full){jdbc.update("UPDATE quality.quality_check_result SET status='unknown',failure_code='CHILD_WORKER_UNAVAILABLE',message='执行器不可用，禁止自动重放' WHERE id=? AND status='running'",claim.result().getId());}
        }
        return status(ref);
    }
    private WorkflowQualityParent resolve(WorkflowQualityParent.Reference ref){
        com.aetheris.security.protocol.transport.SignedHttpClient.Response response;
        try{response=workflow.send(new SignedHttpClient.Request("workflow","POST",WorkflowQualityParent.LOOKUP_PATH,WorkflowQualityParent.LOOKUP_OPERATION,FixedJson.bytes(ref)));}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("父任务来源核对中断");}
        if(response.status()!=200)throw new IllegalStateException("父任务来源当前不可执行");
        var n=FixedJson.parse(response.body(),1048576);FixedJson.fields(n,"canonicalManifest","manifestSha256","status","leaseExpiresAt");long now=Clock.systemUTC().instant().getEpochSecond(),lease=FixedJson.number(n,"leaseExpiresAt",1);
        if(!"running".equals(FixedJson.text(n,"status"))||lease<now+5||lease>now+7320)throw new IllegalStateException("父任务来源租约无效");
        return WorkflowQualityParent.parse(FixedJson.text(n,"canonicalManifest"),FixedJson.hash(n,"manifestSha256"),ref);
    }
    public Result status(WorkflowQualityParent.Reference ref){
        var rows=jdbc.queryForList("""
            SELECT id,rule_id,status,violation_count,threshold_value,duration_ms,finished_at,manifest_sha256,metadata_receipt_id,
                sealed_manifest::text manifest,execution_snapshot
            FROM quality.quality_check_result WHERE parent_origin='workflow' AND parent_run_id=? AND parent_attempt_id=?
                AND parent_fence=? AND parent_manifest_sha256=? AND manifest_sealed_at IS NOT NULL
                AND sealed_manifest->'parent'->'approval'->>'revision'=?
            """,ref.instanceId(),ref.attemptId(),ref.fence(),ref.manifestSha256(),Long.toString(ref.executionRevision()));
        if(rows.size()!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"原父任务尚无已封存子结果");
        var row=rows.getFirst();String canonical=(String)row.get("manifest");
        if(!com.aetheris.security.protocol.BodyHash.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(row.get("manifest_sha256")))throw FixedJson.invalid();
        var manifest=FixedJson.parse(canonical);var d=manifest.get("execution");long threshold=d.get("threshold").isNull()?0:FixedJson.number(d,"threshold",0);
        if(!Objects.equals(row.get("threshold_value"),threshold)&&(!(row.get("threshold_value") instanceof Number n)||n.longValue()!=threshold))throw FixedJson.invalid();
        String state=(String)row.get("status");Long violations=number(row,"violation_count"),receipt=number(row,"metadata_receipt_id"),duration=number(row,"duration_ms");
        if(Set.of("passed","failed").contains(state)&&(violations==null||receipt==null||duration==null||row.get("finished_at")==null||state.equals("passed")!=(violations<=threshold)))throw FixedJson.invalid();
        return new Result(((Number)row.get("id")).longValue(),((Number)row.get("rule_id")).longValue(),(String)row.get("manifest_sha256"),receipt,ref,state,violations,threshold,duration,finished(row.get("finished_at")));
    }
    private static Long number(Map<String,Object> row,String key){return row.get(key)==null?null:((Number)row.get(key)).longValue();}
    private static String finished(Object value){if(value==null)return null;if(value instanceof java.time.OffsetDateTime at)return at.toString();if(value instanceof java.sql.Timestamp at)return at.toInstant().atOffset(java.time.ZoneOffset.UTC).toString();throw FixedJson.invalid();}
    @Override public void close(){workers.shutdownNow();}
}
