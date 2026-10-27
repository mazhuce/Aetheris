package com.aetheris.quality.service;

import com.aetheris.quality.fixedexecution.FixedJson;
import com.aetheris.quality.entity.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Unknown fixed results are reconciled from their exact metadata receipt, including a lost receipt id. */
@Service
public class QualityReceiptReconciler {
    private final JdbcTemplate jdbc;private final QualityQueryClient metadata;private final ExecutionRecoveryService recovery;private final QualityCheckResultService results;
    private final java.util.concurrent.atomic.AtomicLong afterId=new java.util.concurrent.atomic.AtomicLong();
    public QualityReceiptReconciler(JdbcTemplate jdbc,QualityQueryClient metadata,ExecutionRecoveryService recovery,QualityCheckResultService results){this.jdbc=jdbc;this.metadata=metadata;this.recovery=recovery;this.results=results;}
    @Scheduled(fixedDelayString="${aetheris.fixed-execution.reconcile-ms:15000}")
    public synchronized void reconcile(){
        var rows=jdbc.queryForList("SELECT id,execution_revision,manifest_sha256,metadata_receipt_id,sealed_manifest::text manifest FROM quality.quality_check_result WHERE status='unknown' AND manifest_sealed_at IS NOT NULL AND id>? ORDER BY id LIMIT 32",afterId.get());
        if(rows.isEmpty()){afterId.set(0);return;}
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();
        for(var row:rows){if(System.nanoTime()-deadline>=0)break;try{
            long id=((Number)row.get("id")).longValue(),revision=((Number)row.get("execution_revision")).longValue();String hash=(String)row.get("manifest_sha256");
            var receipt=new java.util.concurrent.atomic.AtomicReference<Long>(row.get("metadata_receipt_id")==null?null:((Number)row.get("metadata_receipt_id")).longValue());
            QualityQueryClient.QueryResult response;boolean failedReceipt=false;
            try{response=metadata.reconcile(new QualityQueryClient.Submission(id,revision,hash),receipt.get(),value->{if(jdbc.update("UPDATE quality.quality_check_result SET metadata_receipt_id=? WHERE id=? AND execution_revision=? AND manifest_sha256=? AND status='unknown' AND (metadata_receipt_id IS NULL OR metadata_receipt_id=?)",value,id,revision,hash,value)!=1)throw new IllegalStateException("原回执绑定已变化");receipt.set(value);});}
            catch(QualityQueryClient.QualityExecutionException failed){if(failed.outcomeUnknown()||receipt.get()==null)continue;response=null;failedReceipt=true;}
            // A pending receipt returns null. Its status is not evidence of failure.
            if(response==null&&!failedReceipt)continue;
            var d=FixedJson.parse((String)row.get("manifest")).get("execution");long threshold=d.get("threshold").isNull()?0:FixedJson.number(d,"threshold",0);boolean passed=response!=null&&response.violationCount()<=threshold;
            var result=results.getById(id);result.setStatus(failedReceipt?"error":passed?"passed":"failed");result.setViolationCount(response==null?null:response.violationCount());result.setThresholdValue(threshold);result.setDurationMs(response==null?null:response.durationMs());result.setMessage(failedReceipt?"原metadata回执确认执行错误":passed?"原质量回执核对通过":"原质量回执违规数超过阈值");
            QualityQuarantineRecord quarantine=null;if(!failedReceipt&&!passed){quarantine=new QualityQuarantineRecord();quarantine.setResultId(id);quarantine.setRuleId(result.getRuleId());quarantine.setTargetTable(FixedJson.text(d,"targetTable"));quarantine.setViolationCount(response.violationCount());quarantine.setThresholdValue(threshold);quarantine.setReason("原metadata回执核对违规数超过阈值");}
            recovery.finishSealedReceipt(result,quarantine,revision,hash,receipt.get());
        }catch(RuntimeException unavailable){/* Preserve unknown; there is no submit path in this reconciler. */}
        finally{afterId.set(((Number)row.get("id")).longValue());}}
    }
}
