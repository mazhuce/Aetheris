package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.fixedexecution.*;
import com.aetheris.security.servlet.TrustedUser;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Fixed source execution: only a typed user or a stored cron binding can create a sealed invocation. */
@Service
public class IngestionExecutionService {
    private final IngestionExecutionStore source;private final MetadataIngestionClient client;
    private final NotificationClient notifications;private final ExecutionRecoveryService recovery;
    @Autowired public IngestionExecutionService(IngestionExecutionStore source,MetadataIngestionClient client,NotificationClient notifications,ExecutionRecoveryService recovery) {
        this.source=Objects.requireNonNull(source);this.client=Objects.requireNonNull(client);this.notifications=Objects.requireNonNull(notifications);this.recovery=Objects.requireNonNull(recovery);
    }
    /** Retired constructor for source compatibility. Missing production recovery/store always denies execution. */
    @Deprecated public IngestionExecutionService(IngestionTaskService ignored,IngestionRunService ignoredRuns,MetadataIngestionClient client,NotificationClient notifications){source=null;this.client=client;this.notifications=notifications;recovery=null;}
    @Deprecated public IngestionRun execute(Long id,String username){throw legacy();}
    @Deprecated public IngestionRun execute(Long id,String username,String key){throw legacy();}
    @Deprecated public IngestionRun execute(Long id,String username,String key,Long replayOf){throw legacy();}
    private static IllegalStateException legacy(){return new IllegalStateException("禁止裸用户名执行；必须由可信主体和当前批准版本创建运行");}
    public IngestionRun executeManual(Long taskId,TrustedUser user,String requestKey,Long replayOf) {
        if(source==null||recovery==null)throw legacy();
        return executeClaim(source.claimManual(taskId,IngestionActor.manual(user),requestKey,replayOf));
    }
    public IngestionRun executeScheduled(Long taskId,long generation,String requestKey) {
        if(source==null||recovery==null)throw legacy();return executeClaim(source.claimScheduled(taskId,generation,requestKey));
    }
    private IngestionRun executeClaim(IngestionExecutionStore.Claim claim) {
        if(claim.reused())return claim.run();
        IngestionRun run=claim.run();
        try {
            var result=client.execute(claim.submission(),receipt->source.bindReceipt(run.getId(),receipt));
            run.setStatus("success");run.setRowsRead(result.rowsRead());run.setRowsWritten(result.rowsWritten());run.setDurationMs(result.durationMs());
            run.setFinishedAt(OffsetDateTime.now());
            if(!recovery.finish(run))throw new MetadataIngestionClient.IngestionExecutionException("执行租约已失效，结果需人工核实");
            return run;
        }catch(RuntimeException failure) {
            boolean unknown=!(failure instanceof MetadataIngestionClient.IngestionExecutionException known)||known.outcomeUnknown();
            run.setStatus(unknown?"unknown":"failed");run.setErrorMessage(unknown?"固定执行结果待核实，禁止自动重放":"元数据返回已确认失败回执");
            run.setFailureCode(unknown?"TRANSFER_OUTCOME_UNKNOWN":"UPSTREAM_TRANSFER_FAILED");run.setFinishedAt(OffsetDateTime.now());
            // A false CAS retains the active/unknown slot; expiry reconciliation will fence this worker.
            recovery.finish(run);
            notifications.notifyAdmin(run.getTaskId(),"critical","数据接入执行需处理",run.getErrorMessage());
            throw new MetadataIngestionClient.IngestionExecutionException(run.getErrorMessage(),unknown);
        }
    }
}
