package com.aetheris.workflow.fixedexecution;

import com.aetheris.security.protocol.execution.WorkflowQualityParent;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.util.*;

/** Exact child receipts reconcile their original attempt. Recovery has no submit operation. */
@Service
public class WorkflowQualityFacade {
    private final WorkflowExecutionStore source;private final WorkflowQualityClient client;private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;private final ExecutionClaimCoordinator coordinator;private final TaskInstanceService instances;
    private final java.util.concurrent.atomic.AtomicLong afterId=new java.util.concurrent.atomic.AtomicLong();
    public WorkflowQualityFacade(WorkflowExecutionStore source,WorkflowQualityClient client,JdbcTemplate jdbc,PlatformTransactionManager manager,ExecutionClaimCoordinator coordinator,TaskInstanceService instances){this.source=source;this.client=client;this.jdbc=jdbc;transactions=new TransactionTemplate(manager);this.coordinator=coordinator;this.instances=instances;}
    public TaskInstance execute(WorkflowExecutionStore.Claim claim){
        var e=claim.execution();if(!claim.dependenciesReady())return coordinator.skip(e,"封存依赖尚未成功，未提交质量子任务");
        var ref=new WorkflowQualityParent.Reference(e.instance().getId(),e.attemptId(),e.fencingToken(),claim.revision(),claim.manifestSha256());
        var lookup=source.qualityParent(ref);var parent=WorkflowQualityParent.parse(lookup.canonicalManifest(),lookup.manifestSha256(),ref);
        long budget=Math.max(0,Math.min(3600,lookup.leaseExpiresAt()-OffsetDateTime.now().toEpochSecond()-5));long deadline=System.nanoTime()+java.time.Duration.ofSeconds(budget).toNanos();
        try{
            WorkflowQualityClient.Result result=null;
            try{result=client.submit(ref);}catch(com.aetheris.security.protocol.transport.SignedHttpClient.TransportException lost){/* Delivery is ambiguous; query this parent only. */}
            while(System.nanoTime()-deadline<0){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(result!=null){accept(parent,result);if(result.terminal())return instances.getById(ref.instanceId());if("unknown".equals(result.state()))break;}
                java.util.concurrent.TimeUnit.SECONDS.sleep(1);result=client.status(ref);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(RuntimeException uncertain){/* No fallback to latest(rule), username or another POST. */}
        return coordinator.submissionUnknown(e,"质量子任务结果待核对，禁止自动重放");
    }
    public void accept(WorkflowQualityParent parent,WorkflowQualityClient.Result result){
        var ref=parent.reference();if(result.ruleId()!=parent.qualityRuleId()||result.resultId()<=0)throw FixedJson.invalid();
        transactions.executeWithoutResult(tx->{
            var locked=jdbc.queryForList("""
                SELECT a.id,a.status,a.quality_result_id,a.quality_manifest_sha256,a.metadata_receipt_id,a.manifest_sha256,
                    a.quality_evidence_json::text evidence
                FROM workflow.task_instance i JOIN workflow.execution_attempt a ON a.id=i.active_attempt_id AND a.instance_id=i.id
                WHERE i.id=? AND a.id=? AND i.fencing_token=? AND a.fencing_token=i.fencing_token
                    AND a.execution_revision=? AND a.task_type='quality' AND a.manifest_sealed_at IS NOT NULL
                FOR UPDATE OF i,a
                """,ref.instanceId(),ref.attemptId(),ref.fence(),ref.executionRevision());
            if(locked.size()!=1)throw WorkflowExecutionStore.conflict();var old=locked.getFirst();
            if(!ref.manifestSha256().equals(old.get("manifest_sha256"))||old.get("quality_result_id")!=null&&((Number)old.get("quality_result_id")).longValue()!=result.resultId()
                    ||old.get("quality_manifest_sha256")!=null&&!result.manifestSha256().equals(old.get("quality_manifest_sha256"))
                    ||old.get("metadata_receipt_id")!=null&&!Objects.equals(((Number)old.get("metadata_receipt_id")).longValue(),result.metadataReceiptId()))throw WorkflowExecutionStore.conflict();
            if(!Set.of("running","unknown").contains(old.get("status")))return;
            jdbc.update("UPDATE workflow.execution_attempt SET quality_result_id=?,quality_manifest_sha256=?,metadata_receipt_id=COALESCE(metadata_receipt_id,?) WHERE id=?",result.resultId(),result.manifestSha256(),result.metadataReceiptId(),ref.attemptId());
            if(!result.terminal())return;
            if(result.metadataReceiptId()==null||result.finishedAt()==null)throw FixedJson.invalid();
            String evidence=new String(FixedJson.bytes(result),java.nio.charset.StandardCharsets.UTF_8);
            if(old.get("evidence")!=null&&!FixedJson.parse((String)old.get("evidence")).equals(FixedJson.parse(evidence)))throw WorkflowExecutionStore.conflict();
            boolean success="passed".equals(result.state());String status=success?"success":"failed",attemptStatus=success?"succeeded":"failed";
            var gate=new LinkedHashMap<String,Object>();gate.put("schemaVersion",1);gate.put("kind","quality-gate");gate.put("resultId",result.resultId());gate.put("ruleId",result.ruleId());gate.put("status",result.state());gate.put("violationCount",result.violationCount());gate.put("thresholdValue",result.thresholdValue());gate.put("finishedAt",result.finishedAt());
            String metadata=new String(FixedJson.bytes(gate),java.nio.charset.StandardCharsets.UTF_8),message=success?null:"质量子任务未通过";
            jdbc.update("""
                UPDATE workflow.execution_attempt SET quality_evidence_json=?::jsonb,
                    quality_evidence_sha256=encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex'),
                    status=?,finished_at=clock_timestamp(),duration_ms=?,affected_rows=?,error_code=?,error_message=?,result_metadata_json=?::jsonb
                WHERE id=?
                """,evidence,evidence,attemptStatus,result.durationMs(),result.violationCount(),success?null:"failed".equals(result.state())?"QUALITY_GATE_FAILED":"UPSTREAM_QUALITY_FAILED",message,metadata,ref.attemptId());
            jdbc.update("UPDATE workflow.task_instance SET status=?,finished_at=clock_timestamp(),duration_ms=?,affected_rows=?,error_message=?,lease_expires_at=NULL,updated_at=clock_timestamp() WHERE id=? AND active_attempt_id=? AND fencing_token=?",status,result.durationMs(),result.violationCount(),message,ref.instanceId(),ref.attemptId(),ref.fence());
            jdbc.update("INSERT INTO workflow.execution_event(attempt_id,event_type,to_status,message,details_json) VALUES(?,'quality_child_reconciled',?,'原子质量结果凭证已核对',?::jsonb)",ref.attemptId(),attemptStatus,evidence);
        });
    }
    @Scheduled(fixedDelayString="${aetheris.quality-child.reconcile-ms:15000}")
    public synchronized void reconcile(){
        var rows=jdbc.queryForList("""
            SELECT a.instance_id,a.id,a.fencing_token,a.execution_revision,a.manifest_sha256,a.sealed_manifest::text canonical
            FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.active_attempt_id=a.id AND i.id=a.instance_id AND i.fencing_token=a.fencing_token
            WHERE a.task_type='quality' AND a.manifest_sealed_at IS NOT NULL AND a.status IN ('running','unknown') AND a.id>?
            ORDER BY a.id LIMIT 32
            """,afterId.get());
        if(rows.isEmpty()){afterId.set(0);return;}long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
        for(var row:rows){if(System.nanoTime()-deadline>=0)break;try{
            var ref=new WorkflowQualityParent.Reference(number(row,"instance_id"),number(row,"id"),number(row,"fencing_token"),number(row,"execution_revision"),(String)row.get("manifest_sha256"));
            var parent=WorkflowQualityParent.parse((String)row.get("canonical"),ref.manifestSha256(),ref);var result=client.status(ref);if(result!=null)accept(parent,result);
        }catch(RuntimeException unavailable){/* Unknown is retained. No source lease renewal and no submit from recovery. */}
        finally{afterId.set(number(row,"id"));}}
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
}
