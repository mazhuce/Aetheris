package com.aetheris.workflow.fixedexecution;

import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.*;
import com.aetheris.workflow.service.ExecutionAdapter.RemoteExecution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.*;

/** Gateway FINISHED proves submission only. Completion requires the original JobManager Job. */
@Service
public final class WorkflowFlinkExecutor {
    private final JdbcTemplate jdbc;private final WorkflowEngineAuthority authority;
    private final ExecutionClaimCoordinator coordinator;private final FlinkSqlExecutionClient gateway;private final FlinkJobManagerClient manager;
    public WorkflowFlinkExecutor(JdbcTemplate jdbc,WorkflowEngineAuthority authority,ExecutionClaimCoordinator coordinator,FlinkSqlExecutionClient gateway,FlinkJobManagerClient manager){this.jdbc=jdbc;this.authority=authority;this.coordinator=coordinator;this.gateway=gateway;this.manager=manager;}
    public boolean sealed(long instance,long attempt){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow.execution_attempt WHERE id=? AND instance_id=? AND task_type='flink' AND manifest_sealed_at IS NOT NULL)",Boolean.class,attempt,instance));}
    public TaskInstance execute(WorkflowExecutionStore.Claim claim){
        if(!claim.dependenciesReady())return authority.notStarted(claim,"skipped","封存依赖尚未成功，未提交Flink",false);
        try{
            var source=authority.original(claim);if(!authority.begin(claim,null,FixedJson.text(source.material(),"gatewayOrigin")))return claim.execution().instance();
            String sql=FixedJson.text(source.manifest().get("execution"),"sql").replace("${bizDate}",FixedJson.text(source.manifest().get("parameters"),"bizDate"));
            gateway.startSealed(source.material().path("gatewayOrigin").asText(),sql,"streaming".equals(source.material().path("mode").asText()),claim.execution().leaseExpiresAt().toInstant().toEpochMilli(),new FlinkSqlExecutionClient.StepObserver(){
                public void before(int i,String kind,String statement){authority.beforeStep(claim,i,kind,statement);}
                public void returned(int i,String kind,String session,String operation){authority.afterStep(claim,i,kind,session,operation);}
            });
            return claim.execution().instance();
        }catch(RuntimeException unavailable){
            try{return authority.notStarted(claim,"failed","Flink派发前校验未通过，未创建远端会话",false);}
            catch(RuntimeException mayHaveStarted){return coordinator.submissionUnknown(claim.execution(),"Flink提交结果未核实，保留原始步骤且禁止重放");}
        }
    }
    public TaskInstance reconcile(WorkflowExecutionStore.Claim claim){
        var source=authority.original(claim);if(source.token()==null||source.handle()==null)return claim.execution().instance();
        String[] handles=source.handle().split("/",2);if(handles.length!=2)throw FixedJson.invalid();
        String endpoint=FixedJson.text(source.material(),"gatewayOrigin"),managerOrigin=FixedJson.text(source.material(),"jobManagerOrigin");
        String job=jdbc.queryForObject("SELECT flink_job_id FROM workflow.execution_attempt WHERE id=?",String.class,claim.execution().attemptId());
        if(job==null){job=gateway.submittedJobIdSealed(endpoint,handles[0],handles[1]);if(job==null)return claim.execution().instance();
            if(!coordinator.bindDiscoveredFlinkJob(claim.execution().instance().getId(),claim.execution().attemptId(),source.handle(),job))throw WorkflowExecutionStore.conflict();}
        var remote=manager.statusAt(managerOrigin,job);String mode=FixedJson.text(source.material(),"mode");
        boolean unexpectedFinish="FINISHED".equals(remote.state())&&"streaming".equals(mode);
        String state=switch(remote.state()){
            case "FINISHED"->switch(mode){case "bounded","batch"->"succeeded";case "streaming"->"failed";default->throw FixedJson.invalid();};
            case "FAILED"->"failed";case "CANCELED"->"cancelled";default->null;
        };
        // A request/legacy lifecycle intent is not proof of an approved stop. Until that
        // contract exists, a finished unbounded job is a confirmed failure, never DAG success.
        if(state!=null)return authority.complete(claim,new RemoteExecution(source.handle(),state,null,null,
            unexpectedFinish?"FLINK_UNEXPECTED_FINISH":null,
            unexpectedFinish?"Flink持续任务意外结束，未发现批准停止的结果凭证":null,
            Map.of("jobId",job,"jobManagerOrigin",managerOrigin,"jobState",remote.state(),"flinkMode",mode),
            "cancelled".equals(state)?OffsetDateTime.now().toString():null));
        String status=jdbc.queryForObject("SELECT status FROM workflow.execution_attempt WHERE id=?",String.class,claim.execution().attemptId());
        if("cancel_requested".equals(status)||claim.execution().leaseExpiresAt().isBefore(OffsetDateTime.now())){coordinator.requestCancel(claim.execution());manager.cancelAt(managerOrigin,job);}
        return claim.execution().instance();
    }
    public TaskInstance cancel(long instance,long attempt){var claim=authority.claim(instance,attempt);var source=authority.original(claim);
        if(source.token()==null)return authority.notStarted(claim,"cancelled","派发前取消，未创建Flink会话",false);
        coordinator.requestCancel(claim.execution());return reconcile(claim);
    }
    public Map<String,Object> status(long instance,long attempt){var claim=authority.claim(instance,attempt);reconcile(claim);var source=authority.original(claim);var status=jdbc.queryForObject("SELECT status FROM workflow.execution_attempt WHERE id=?",String.class,attempt);return Map.of("instanceStatus",claim.execution().instance().getStatus(),"attemptStatus",status,"remoteHandle",Objects.toString(source.handle(),""),"terminal",Set.of("succeeded","failed","cancelled","timed_out").contains(status));}
    @Scheduled(fixedDelayString="${aetheris.flink-sql-gateway.poll-interval-ms:1000}")
    public void reconcile(){for(var row:jdbc.queryForList("SELECT a.id,a.instance_id FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.active_attempt_id=a.id AND i.fencing_token=a.fencing_token WHERE a.task_type='flink' AND a.manifest_sealed_at IS NOT NULL AND a.status IN ('submitting','running','unknown','cancel_requested') ORDER BY a.id LIMIT 64")){
        try{reconcile(authority.claim(((Number)row.get("instance_id")).longValue(),((Number)row.get("id")).longValue()));}
        catch(RuntimeException unavailable){/* No status inference or replay from a failed readback. */}
    }}
}
