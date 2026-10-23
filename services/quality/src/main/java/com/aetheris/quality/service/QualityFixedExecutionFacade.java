package com.aetheris.quality.service;

import com.aetheris.quality.entity.*;
import com.aetheris.quality.fixedexecution.*;
import com.aetheris.security.servlet.TrustedUser;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Remote execution never runs while a source row lock is held. Local completion is one fenced transaction. */
@Service
public class QualityFixedExecutionFacade {
    private final QualityExecutionStore store;private final QualityQueryClient client;private final ExecutionRecoveryService recovery;private final NotificationClient notifications;
    public QualityFixedExecutionFacade(QualityExecutionStore store,QualityQueryClient client,ExecutionRecoveryService recovery,NotificationClient notifications){
        this.store=Objects.requireNonNull(store);this.client=Objects.requireNonNull(client);this.recovery=Objects.requireNonNull(recovery);this.notifications=Objects.requireNonNull(notifications);
    }
    public QualityCheckResult manual(long id,TrustedUser user){return execute(store.claimManual(id,QualityActor.manual(user),null));}
    public QualityCheckResult scheduled(long id,long generation,String key){return execute(store.claimScheduled(id,generation,key));}
    private void safeNotify(Long id,String level,String title,String message){try{notifications.notifyAdmin(id,level,title,message);}catch(RuntimeException unavailable){/* Never repeat or reclassify an execution because notification delivery failed. */}}
    private QualityCheckResult execute(QualityExecutionStore.Claim claim){
        if(claim.reused())return claim.result();var result=claim.result();
        try {
            var response=client.execute(claim.submission(),receipt->store.bindReceipt(result.getId(),receipt));
            boolean passed=response.violationCount()<=claim.threshold();
            result.setStatus(passed?"passed":"failed");result.setThresholdValue(claim.threshold());result.setViolationCount(response.violationCount());result.setDurationMs(response.durationMs());
            result.setMessage(passed?"质量检查通过":"违规数超过阈值");result.setFinishedAt(OffsetDateTime.now());
            QualityQuarantineRecord quarantine=null;
            if(!passed){quarantine=new QualityQuarantineRecord();quarantine.setResultId(result.getId());quarantine.setRuleId(result.getRuleId());quarantine.setTargetTable(claim.targetTable());
                quarantine.setViolationCount(response.violationCount());quarantine.setThresholdValue(claim.threshold());quarantine.setReason("违规数超过阈值");quarantine.setStatus("open");}
            if(!recovery.finish(result,quarantine))throw new QualityQueryClient.QualityExecutionException("本地完成租约已失效",true);
            if(!passed)safeNotify(result.getRuleId(),"warning","质量检查未通过","封存规则的违规数超过阈值");
            return result;
        }catch(RuntimeException failure){
            boolean unknown=!(failure instanceof QualityQueryClient.QualityExecutionException known)||known.outcomeUnknown();
            result.setStatus(unknown?"unknown":"error");result.setViolationCount(null);result.setDurationMs(null);result.setThresholdValue(claim.threshold());
            result.setFailureCode(unknown?"QUALITY_OUTCOME_UNKNOWN":"UPSTREAM_QUALITY_FAILED");result.setMessage(unknown?"固定质量结果待核实，禁止自动重放":"元数据返回已确认失败回执");result.setFinishedAt(OffsetDateTime.now());
            // Failed completion leaves a running/unknown concurrency slot for expiry or explicit reconciliation.
            try{recovery.finish(result,null);}catch(RuntimeException persistUnknown){/* original slot is retained; never execute again */}
            safeNotify(result.getRuleId(),"critical","质量检查执行需处理",result.getMessage());
            throw new QualityQueryClient.QualityExecutionException(result.getMessage(),unknown);
        }
    }
}
