package com.aetheris.workflow.fixedexecution;

import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.*;
import com.aetheris.workflow.service.ExecutionAdapter.*;
import org.springframework.jdbc.core.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.*;

/** Production Spark dispatch consumes only sealed inputs; restart is readback only. */
@Service
public final class WorkflowSparkExecutor {
    private final JdbcTemplate jdbc;private final WorkflowEngineAuthority authority;
    private final SparkResourceQueueService queue;private final SparkBatchExecutionAdapter adapter;
    private final ExecutionClaimCoordinator coordinator;
    public WorkflowSparkExecutor(JdbcTemplate jdbc,WorkflowEngineAuthority authority,SparkResourceQueueService queue,
            SparkBatchExecutionAdapter adapter,ExecutionClaimCoordinator coordinator){
        this.jdbc=jdbc;this.authority=authority;this.queue=queue;this.adapter=adapter;this.coordinator=coordinator;
    }
    public boolean sealed(long attempt){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow.execution_attempt WHERE id=? AND task_type='spark' AND manifest_sealed_at IS NOT NULL)",Boolean.class,attempt));}
    public WorkflowExecutionStore.Claim claim(long attempt){
        var rows=jdbc.queryForList("SELECT instance_id,fencing_token,attempt_no,execution_revision,manifest_sha256,lease_expires_at FROM workflow.execution_attempt WHERE id=? AND manifest_sealed_at IS NOT NULL AND task_type='spark'",attempt);
        if(rows.size()!=1)throw WorkflowExecutionStore.conflict();var r=rows.getFirst();
        var i=jdbc.queryForObject("SELECT * FROM workflow.task_instance WHERE id=?",new BeanPropertyRowMapper<>(TaskInstance.class),r.get("instance_id"));
        var lease=jdbc.queryForObject("SELECT lease_expires_at FROM workflow.execution_attempt WHERE id=?",OffsetDateTime.class,attempt);
        var e=new ExecutionClaimCoordinator.ExecutionClaim(i,attempt,((Number)r.get("attempt_no")).intValue(),((Number)r.get("fencing_token")).longValue(),lease);
        return new WorkflowExecutionStore.Claim(e,((Number)r.get("execution_revision")).longValue(),(String)r.get("manifest_sha256"),true);
    }
    public TaskInstance execute(WorkflowExecutionStore.Claim claim){
        if(!claim.dependenciesReady())return authority.notStarted(claim,"skipped","封存依赖尚未成功，未提交Spark",false);
        var source=authority.original(claim);var n=source.material();
        queue.enqueueFrozen(claim.execution(),FixedJson.number(n,"environmentId",1),Math.toIntExact(FixedJson.number(n,"cores",1)),Math.toIntExact(FixedJson.number(n,"memoryMb",512)));
        var admission=queue.tryAdmit(claim.execution().attemptId());return admission==null?claim.execution().instance():dispatch(claim,admission);
    }
    public TaskInstance dispatch(WorkflowExecutionStore.Claim claim,SparkResourceQueueService.Admission admission){
        boolean owned=false,postStarted=false;
        try{
            owned=authority.begin(claim,admission.runnerId(),SparkBatchExecutionAdapter.normalizeBaseUrl(admission.baseUrl()));
            if(!owned)return claim.execution().instance();
            var source=authority.original(claim);var n=source.material();String digest=FixedJson.hash(n,"artifactSha256");
            adapter.validate(source.origin(),FixedJson.text(n,"artifactKey"),digest);
            Map<String,Object> params=new LinkedHashMap<>();n.get("parameters").fields().forEachRemaining(e->params.put(e.getKey(),e.getValue()));
            params.put("bizDate",FixedJson.text(source.manifest().get("parameters"),"bizDate"));params.put("attemptId",claim.execution().attemptId());
            String main=n.get("mainClass").isNull()?null:FixedJson.text(n,"mainClass");
            var request=new Submission(source.requestKey(),FixedJson.text(n,"artifactKey"),FixedJson.text(n,"artifactType"),digest,main,params,
                Math.toIntExact(FixedJson.number(n,"cores",1)),Math.toIntExact(FixedJson.number(n,"memoryMb",512)),Math.toIntExact(FixedJson.number(n,"timeoutSeconds",1)));
            postStarted=true;var handle=adapter.submit(source.origin(),request);return authority.submitted(claim,handle);
        }catch(RuntimeException failure){
            boolean rejected=failure instanceof SparkRunnerException safe&&!safe.isSubmissionOutcomeUnknown();
            if(!postStarted||rejected){
                try{var failed=authority.notStarted(claim,"failed","引擎派发前校验未通过，未开始业务执行",owned);queue.release(claim.execution().attemptId(),"sealed_not_started");return failed;}
                catch(RuntimeException cannotProve){/* A competing owner may already have dispatched. */}
            }
            return coordinator.submissionUnknown(claim.execution(),"Spark结果尚未核实，保留容量并按原始来源核对");
        }
    }
    public TaskInstance reconcile(WorkflowExecutionStore.Claim claim){
        var source=authority.original(claim);if(source.token()==null)return claim.execution().instance();
        String digest=FixedJson.hash(source.material(),"artifactSha256");
        if(source.handle()==null){var handle=adapter.reconcile(source.origin(),source.requestKey()).orElse(null);if(handle==null)return claim.execution().instance();
            if(!digest.equals(handle.artifactDigest()))throw FixedJson.invalid();authority.submitted(claim,handle);source=authority.original(claim);}
        RemoteExecution remote=adapter.statusBound(source.origin(),source.handle(),source.requestKey(),digest);
        boolean expired=claim.execution().leaseExpiresAt()!=null&&claim.execution().leaseExpiresAt().isBefore(OffsetDateTime.now());
        if(!remote.terminal()&&expired){coordinator.requestCancel(claim.execution());remote=adapter.cancelBound(source.origin(),source.handle(),source.requestKey(),digest);}
        if(Set.of("succeeded","failed","cancelled","timed_out").contains(remote.status())){
            var completed=authority.complete(claim,remote);queue.release(claim.execution().attemptId(),remote.status());return completed;
        }
        if("unknown".equals(remote.status()))return coordinator.submissionUnknown(claim.execution(),"原始Runner状态未知，保留物理容量");
        jdbc.update("UPDATE workflow.execution_attempt SET heartbeat_at=clock_timestamp() WHERE id=? AND fencing_token=? AND status IN ('running','cancel_requested','unknown')",claim.execution().attemptId(),claim.execution().fencingToken());
        return claim.execution().instance();
    }
    public TaskInstance cancel(WorkflowExecutionStore.Claim claim){
        var source=authority.original(claim);
        if(source.token()==null){var done=authority.notStarted(claim,"cancelled","派发前取消，未提交引擎",false);queue.release(claim.execution().attemptId(),"sealed_cancelled_before_dispatch");return done;}
        coordinator.requestCancel(claim.execution());if(source.handle()==null)return coordinator.submissionUnknown(claim.execution(),"取消请求等待原始句柄核对，保留容量");
        var remote=adapter.cancelBound(source.origin(),source.handle(),source.requestKey(),FixedJson.hash(source.material(),"artifactSha256"));
        if(Set.of("succeeded","failed","cancelled","timed_out").contains(remote.status())){var done=authority.complete(claim,remote);queue.release(claim.execution().attemptId(),remote.status());return done;}
        return claim.execution().instance();
    }
    public LogPage logs(WorkflowExecutionStore.Claim claim,long offset,int limit){var s=authority.original(claim);if(s.handle()==null)throw WorkflowExecutionStore.conflict();return adapter.logsBound(s.origin(),s.handle(),s.requestKey(),FixedJson.hash(s.material(),"artifactSha256"),offset,limit);}
    @Scheduled(fixedDelayString="${aetheris.spark-runner.queue-expiry-interval-ms:5000}")
    public void expireQueued(){
        for(Long id:jdbc.query("SELECT id FROM workflow.execution_attempt WHERE task_type='spark' AND manifest_sealed_at IS NOT NULL AND engine_dispatch_token IS NULL AND status IN ('queued','submitting') AND (lease_expires_at<clock_timestamp() OR queued_at+interval '300 seconds'<=clock_timestamp()) ORDER BY id LIMIT 32",(r,i)->r.getLong(1))){
            try{var claim=claim(id);authority.notStarted(claim,"failed","排队租约到期，未提交引擎",false);queue.release(id,"sealed_queue_expired");}
            catch(RuntimeException changed){/* Only the still undispatched current attempt may release capacity. */}
        }
    }
}
