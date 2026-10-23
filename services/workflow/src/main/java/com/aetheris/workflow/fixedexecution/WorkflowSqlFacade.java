package com.aetheris.workflow.fixedexecution;

import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.*;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Exactly one sealed attempt and one submit; an uncertain outcome retains its slot. */
@Service
public class WorkflowSqlFacade {
    private final WorkflowExecutionStore store;private final SqlExecutionClient client;private final ExecutionClaimCoordinator coordinator;
    private final TaskLogService logs;private final NotificationClient notifications;
    public WorkflowSqlFacade(WorkflowExecutionStore store,SqlExecutionClient client,ExecutionClaimCoordinator coordinator,TaskLogService logs,NotificationClient notifications){
        this.store=Objects.requireNonNull(store);this.client=Objects.requireNonNull(client);this.coordinator=Objects.requireNonNull(coordinator);
        this.logs=Objects.requireNonNull(logs);this.notifications=Objects.requireNonNull(notifications);
    }
    public TaskInstance manual(long id,String date,WorkflowActor actor){return execute(store.claimManual(id,date,actor));}
    public TaskInstance scheduled(long id,String date,long generation){return execute(store.claimScheduled(id,date,generation));}
    private TaskInstance execute(WorkflowExecutionStore.Claim source){
        var claim=source.execution();if(!source.dependenciesReady()){log(claim,"warn","封存依赖尚未成功，未提交SQL");return coordinator.skip(claim,"封存依赖尚未成功，未提交SQL");}
        log(claim,"info","开始执行已批准的封存SQL attempt");
        try{
            var result=client.execute(source.submission(),claim.leaseExpiresAt(),receipt->store.bindReceipt(source,receipt));
            // Nullable driver row counts remain unknown, never a fabricated zero.
            var completed=coordinator.completeFixedSql(claim,result);log(claim,"info","固定SQL执行成功");return completed;
        }catch(SqlExecutionException safe){
            var completed=safe.outcomeUnknown()?coordinator.submissionUnknown(claim,"SQL结果尚未可靠确认，禁止自动重放"):coordinator.failFixedSql(claim,"元数据已返回失败回执");
            failure(claim,safe.outcomeUnknown());return completed;
        }catch(RuntimeException uncertain){var unknown=coordinator.submissionUnknown(claim,"SQL完成状态未可靠持久化，禁止自动重放");failure(claim,true);return unknown;}
    }
    private void log(ExecutionClaimCoordinator.ExecutionClaim claim,String level,String message){
        try{var row=new com.aetheris.workflow.entity.TaskLog();row.setInstanceId(claim.instance().getId());row.setAttemptId(claim.attemptId());row.setLevel(level);row.setMessage(message);logs.save(row);}catch(RuntimeException ignored){/* Diagnostic failures never change an execution result. */}
    }
    private void failure(ExecutionClaimCoordinator.ExecutionClaim claim,boolean unknown){
        String message=unknown?"SQL结果待核对，禁止自动重放":"SQL执行失败";log(claim,"error",message);
        try{notifications.notifyAdmin(claim.instance().getTaskId(),claim.instance().getBizDate(),"critical",message,message);}catch(RuntimeException ignored){/* Notification never re-executes business work. */}
    }
}
