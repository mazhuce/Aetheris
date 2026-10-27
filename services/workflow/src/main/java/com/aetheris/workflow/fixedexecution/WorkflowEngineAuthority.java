package com.aetheris.workflow.fixedexecution;

import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.ExecutionAdapter.ManagedHandle;
import com.aetheris.workflow.service.ExecutionAdapter.RemoteExecution;
import com.aetheris.workflow.service.ExecutionClaimCoordinator.ExecutionClaim;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.BeanPropertyRowMapper;

/** A durable one-shot dispatch marker. Readback never reconstructs authority from a username. */
@Component
public final class WorkflowEngineAuthority {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final WorkflowExecutionStore store;private final WorkflowEngineMaterial materials;
    private final WorkflowEngineAuthorizationClient authorization;
    public WorkflowEngineAuthority(JdbcTemplate jdbc,PlatformTransactionManager manager,WorkflowExecutionStore store,
            WorkflowEngineMaterial materials,WorkflowEngineAuthorizationClient authorization){
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.store=store;this.materials=materials;this.authorization=authorization;
    }
    public record Source(WorkflowExecutionStore.Claim claim,JsonNode manifest,JsonNode material,String materialHash,
            UUID token,String origin,Long runnerId,String handle,String receiptHash){
        public ExecutionClaim execution(){return claim.execution();}
        public String requestKey(){return "attempt-"+execution().attemptId()+"-f"+execution().fencingToken();}
        public String engine(){return FixedJson.text(material,"engine");}
    }
    public Source original(WorkflowExecutionStore.Claim claim){return load(claim,false);}
    public WorkflowExecutionStore.Claim claim(long instance,long attempt){
        var rows=jdbc.queryForList("SELECT fencing_token,attempt_no,execution_revision,manifest_sha256,lease_expires_at FROM workflow.execution_attempt WHERE id=? AND instance_id=? AND manifest_sealed_at IS NOT NULL AND task_type IN ('spark','flink')",attempt,instance);
        if(rows.size()!=1)throw WorkflowExecutionStore.conflict();var r=rows.getFirst();
        var i=jdbc.queryForObject("SELECT * FROM workflow.task_instance WHERE id=?",new BeanPropertyRowMapper<>(TaskInstance.class),instance);
        var lease=jdbc.queryForObject("SELECT lease_expires_at FROM workflow.execution_attempt WHERE id=?",OffsetDateTime.class,attempt);
        var e=new ExecutionClaim(i,attempt,((Number)r.get("attempt_no")).intValue(),((Number)r.get("fencing_token")).longValue(),lease);
        var claim=new WorkflowExecutionStore.Claim(e,((Number)r.get("execution_revision")).longValue(),(String)r.get("manifest_sha256"),true);original(claim);return claim;
    }
    public void beforeStep(WorkflowExecutionStore.Claim claim,int index,String kind,String statement){
        var source=tx.execute(t->load(claim,true));if(source.token()==null||!"flink".equals(source.engine()))throw WorkflowExecutionStore.conflict();
        String sql=FixedJson.text(source.manifest().get("execution"),"sql").replace("${bizDate}",FixedJson.text(source.manifest().get("parameters"),"bizDate"));
        var statements=com.aetheris.workflow.service.FlinkSqlScript.statements(sql,"streaming".equals(source.material().path("mode").asText()));
        if(index==0?(!"session".equals(kind)||!statement.isEmpty()):index<1||index>statements.size()
                ||!statements.get(index-1).equals(statement)||!(index==statements.size()?"insert":"setup").equals(kind))throw FixedJson.invalid();
        if(!authorization.authorize(source.manifest(),claim.manifestSha256(),source.materialHash()))throw WorkflowExecutionStore.conflict();
        tx.executeWithoutResult(t->{load(claim,true);var step=FixedJson.JSON.createObjectNode().put("index",index).put("kind",kind).put("phase","before").put("statementSha256",WorkflowEngineMaterial.digest(statement));
            if(jdbc.update("""
                UPDATE workflow.execution_attempt SET engine_steps_json=engine_steps_json||jsonb_build_array(?::jsonb)
                WHERE id=? AND fencing_token=? AND engine_dispatch_token=?::uuid AND status IN ('submitting','running')
                  AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(engine_steps_json) step WHERE step->>'phase'='before' AND step->>'index'=?::text)
                """,step.toString(),claim.execution().attemptId(),claim.execution().fencingToken(),source.token(),index)!=1)throw WorkflowExecutionStore.conflict();});
    }
    public void afterStep(WorkflowExecutionStore.Claim claim,int index,String kind,String session,String operation){
        if(session==null||!session.matches("(?:[a-fA-F0-9]{32}|[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})")
            ||(index==0?operation!=null:operation==null||!operation.matches("(?:[a-fA-F0-9]{32}|[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})")))throw FixedJson.invalid();
        tx.executeWithoutResult(t->{var s=load(claim,false);var step=FixedJson.JSON.createObjectNode().put("index",index).put("kind",kind).put("phase","returned").put("session",session).put("operation",operation);
            if(jdbc.update("""
                UPDATE workflow.execution_attempt SET engine_steps_json=engine_steps_json||jsonb_build_array(?::jsonb)
                WHERE id=? AND fencing_token=? AND engine_dispatch_token=?::uuid
                  AND engine_steps_json->-1->>'phase'='before' AND engine_steps_json->-1->>'index'=?::text AND engine_steps_json->-1->>'kind'=?
                """,step.toString(),claim.execution().attemptId(),claim.execution().fencingToken(),s.token(),index,kind)!=1)throw WorkflowExecutionStore.conflict();
            if("insert".equals(kind))submitted(claim,new ManagedHandle(session+"/"+operation,s.requestKey(),"running",null,null));});
    }
    private Source load(WorkflowExecutionStore.Claim claim,boolean current){
        var e=claim.execution();var locked=current?store.lockDefinition(e.instance().getTaskId()):null;
        var rows=jdbc.queryForList("""
            SELECT a.sealed_manifest::text manifest,a.manifest_sha256,a.execution_revision,
              (a.sealed_manifest->'execution'->'engineDefinition')::text material,a.engine_dispatch_token,
              a.engine_runner_origin,a.runner_id,a.remote_handle,a.engine_receipt_sha256,
              (a.status IN ('queued','submitting','running') AND i.status IN ('queued','submitting','running')
                AND a.lease_expires_at>clock_timestamp() AND i.lease_expires_at>clock_timestamp()
                AND t.status='enabled' AND t.execution_revision=a.execution_revision AND t.execution_approval=a.approval_json
                AND t.execution_definition_json=a.sealed_manifest->'execution'
                AND ((a.invocation_json->>'kind'='manual' AND a.source_schedule_generation IS NULL AND a.pipeline_run_id IS NULL)
                  OR (a.invocation_json->>'kind'='cron' AND a.invocation_json=t.cron_actor
                    AND t.cron_approved_execution_revision=a.execution_revision AND a.source_schedule_generation=t.schedule_version
                    AND a.source_binding_generation=t.cron_binding_generation AND a.pipeline_run_id IS NULL)
                  OR (a.invocation_json->>'kind'='pipeline' AND workflow.pipeline_binding_valid(a)))) current_source
            FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.id=a.instance_id
              JOIN workflow.task t ON t.id=i.task_id
            WHERE a.id=? AND a.instance_id=? AND a.fencing_token=? AND i.active_attempt_id=a.id AND i.fencing_token=a.fencing_token
              AND a.manifest_sealed_at IS NOT NULL AND a.task_type IN ('spark','flink')
              AND a.parameters_json=jsonb_build_object('bizDate',i.biz_date)
              AND a.manifest_sha256=encode(sha256(convert_to(a.sealed_manifest::text,'UTF8')),'hex')
            """,e.attemptId(),e.instance().getId(),e.fencingToken());
        if(rows.size()!=1)throw WorkflowExecutionStore.conflict();var row=rows.getFirst();
        if(!claim.manifestSha256().equals(row.get("manifest_sha256"))||claim.revision()!=((Number)row.get("execution_revision")).longValue()
                ||(current&&!Boolean.TRUE.equals(row.get("current_source"))))throw WorkflowExecutionStore.conflict();
        var manifest=FixedJson.parse((String)row.get("manifest"));var material=FixedJson.parse((String)row.get("material"));
        String engine=FixedJson.text(material,"engine");WorkflowEngineMaterial.validate(material,engine);
        if(!("workflow.fixed-"+engine+".dispatch").equals(FixedJson.text(manifest,"operation"))
                ||!"workflow".equals(FixedJson.text(manifest,"origin"))||FixedJson.number(manifest,"schemaVersion",1)!=1
                ||FixedJson.number(manifest,"runId",1)!=e.instance().getId()||FixedJson.number(manifest,"attemptId",1)!=e.attemptId()
                ||FixedJson.number(manifest,"fence",1)!=e.fencingToken())throw FixedJson.invalid();
        if(current)materials.requireCurrent(locked);
        return new Source(claim,manifest,material,WorkflowEngineMaterial.digest((String)row.get("material")),
                (UUID)row.get("engine_dispatch_token"),(String)row.get("engine_runner_origin"),
                row.get("runner_id") instanceof Number r?r.longValue():null,(String)row.get("remote_handle"),(String)row.get("engine_receipt_sha256"));
    }
    /** Returns true only to the transaction that acquired dispatch ownership. It is never retried after uncertainty. */
    public boolean begin(WorkflowExecutionStore.Claim claim,Long runner,String origin){
        Source before=tx.execute(t->load(claim,true));if(before.token()!=null)return false;
        if(!authorization.authorize(before.manifest(),claim.manifestSha256(),before.materialHash()))throw new IllegalStateException("执行主体或批准主体已失去权限");
        return Boolean.TRUE.equals(tx.execute(t->{var current=load(claim,true);if(current.token()!=null)return false;
            int changed=jdbc.update("""
                UPDATE workflow.execution_attempt SET engine_dispatch_token=?::uuid,engine_dispatch_started_at=clock_timestamp(),
                  engine_runner_origin=?,runner_id=? WHERE id=? AND fencing_token=? AND engine_dispatch_token IS NULL
                    AND status IN ('submitting','running')
                """,UUID.randomUUID(),origin,runner,claim.execution().attemptId(),claim.execution().fencingToken());
            if(changed!=1)throw WorkflowExecutionStore.conflict();event(claim,"engine_dispatch_owned","仅本次持有者可提交引擎请求");return true;}));
    }
    public TaskInstance notStarted(WorkflowExecutionStore.Claim claim,String state,String reason,boolean explicitBackendRejection){
        if(!Set.of("failed","cancelled","skipped").contains(state))throw FixedJson.invalid();
        return tx.execute(t->{var source=load(claim,false);var result=base(source).put("state","not_started").put("reason",reason);
            int changed=jdbc.update("""
                UPDATE workflow.execution_attempt a SET status=?,finished_at=clock_timestamp(),error_message=?,
                  engine_result_json=?::jsonb,engine_result_sha256=encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex')
                WHERE id=? AND fencing_token=? AND engine_receipt_json IS NULL
                  AND (engine_dispatch_token IS NULL OR ?::boolean) AND status IN ('queued','submitting','running')
                """,state,reason,result.toString(),result.toString(),source.execution().attemptId(),source.execution().fencingToken(),explicitBackendRejection);
            if(changed!=1)throw WorkflowExecutionStore.conflict();finishInstance(source,state,null,reason);event(claim,"engine_not_started",reason);return source.execution().instance();});
    }
    public TaskInstance submitted(WorkflowExecutionStore.Claim claim,ManagedHandle handle){
        return tx.execute(t->{var source=load(claim,false);
            if(source.token()==null||!source.requestKey().equals(handle.requestKey())||handle.id()==null||handle.id().isBlank()
                    ||("spark".equals(source.engine())&&!FixedJson.hash(source.material(),"artifactSha256").equals(handle.artifactDigest())))throw FixedJson.invalid();
            var receipt=base(source).put("engineDefinitionSha256",source.materialHash()).put("origin",source.origin())
                .put("requestKey",source.requestKey()).put("remoteHandle",handle.id()).put("artifactSha256",handle.artifactDigest());
            int changed=jdbc.update("""
                UPDATE workflow.execution_attempt SET status=CASE WHEN status='cancel_requested' THEN status ELSE 'running' END,
                  remote_handle=?,application_id=?,submitted_at=COALESCE(submitted_at,clock_timestamp()),
                  started_at=COALESCE(started_at,clock_timestamp()),heartbeat_at=clock_timestamp(),
                  engine_receipt_json=?::jsonb,engine_receipt_sha256=encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex')
                WHERE id=? AND fencing_token=? AND status IN ('submitting','running','unknown','cancel_requested')
                  AND (engine_receipt_json IS NULL OR engine_receipt_json=?::jsonb)
                """,handle.id(),handle.applicationId(),receipt.toString(),receipt.toString(),source.execution().attemptId(),source.execution().fencingToken(),receipt.toString());
            if(changed!=1)throw WorkflowExecutionStore.conflict();
            if(jdbc.update("UPDATE workflow.task_instance SET status=CASE WHEN status='cancel_requested' THEN status ELSE 'running' END,heartbeat_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=? AND active_attempt_id=? AND fencing_token=?",source.execution().instance().getId(),source.execution().attemptId(),source.execution().fencingToken())!=1)throw WorkflowExecutionStore.conflict();
            source.execution().instance().setStatus("running");event(claim,"engine_receipt_bound","原始引擎句柄已绑定");return source.execution().instance();});
    }
    public TaskInstance complete(WorkflowExecutionStore.Claim claim,RemoteExecution remote){
        if(!Set.of("succeeded","failed","cancelled","timed_out").contains(remote.status()))throw FixedJson.invalid();
        if(("cancelled".equals(remote.status())||"timed_out".equals(remote.status()))&&remote.cancellationConfirmedAt()==null)throw FixedJson.invalid();
        return tx.execute(t->{var source=load(claim,false);if(source.receiptHash()==null||!Objects.equals(source.handle(),remote.id()))throw FixedJson.invalid();
            var result=base(source).put("state",remote.status()).put("receiptSha256",source.receiptHash());
            result.set("remote",FixedJson.JSON.valueToTree(remote));
            String metadata=FixedJson.JSON.valueToTree(remote.result()==null?Map.of():remote.result()).toString();
            int changed=jdbc.update("""
                UPDATE workflow.execution_attempt SET status=?,finished_at=clock_timestamp(),heartbeat_at=clock_timestamp(),
                  affected_rows=?,error_message=?,result_metadata_json=?::jsonb,
                  engine_result_json=?::jsonb,engine_result_sha256=encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex')
                WHERE id=? AND fencing_token=? AND status IN ('running','unknown','cancel_requested') AND engine_result_json IS NULL
                """,remote.status(),remote.affectedRows(),safeError(remote),metadata,result.toString(),result.toString(),source.execution().attemptId(),source.execution().fencingToken());
            if(changed!=1)throw WorkflowExecutionStore.conflict();finishInstance(source,remote.status(),remote.affectedRows(),safeError(remote));
            event(claim,"engine_terminal_bound","原始句柄终态已核对");return source.execution().instance();});
    }
    private static String safeError(RemoteExecution remote){return "succeeded".equals(remote.status())?null:"引擎返回"+remote.status()+"；详细诊断按授权查看";}
    private void finishInstance(Source source,String attemptState,Long rows,String reason){
        String state="succeeded".equals(attemptState)?"success":attemptState;
        if(jdbc.update("""
            UPDATE workflow.task_instance SET status=?,affected_rows=?,error_message=?,finished_at=clock_timestamp(),
              duration_ms=greatest(0,extract(epoch FROM(clock_timestamp()-started_at))*1000)::bigint,
              heartbeat_at=clock_timestamp(),lease_expires_at=NULL,updated_at=clock_timestamp()
            WHERE id=? AND active_attempt_id=? AND fencing_token=?
            """,state,rows,reason,source.execution().instance().getId(),source.execution().attemptId(),source.execution().fencingToken())!=1)throw WorkflowExecutionStore.conflict();
        source.execution().instance().setStatus(state);source.execution().instance().setAffectedRows(rows);source.execution().instance().setErrorMessage(reason);
    }
    private ObjectNode base(Source source){return FixedJson.JSON.createObjectNode().put("schemaVersion",1).put("engine",source.engine())
        .put("instanceId",source.execution().instance().getId()).put("attemptId",source.execution().attemptId()).put("fence",source.execution().fencingToken()).put("manifestSha256",source.claim().manifestSha256());}
    private void event(WorkflowExecutionStore.Claim claim,String type,String message){jdbc.update("INSERT INTO workflow.execution_event(attempt_id,event_type,message,details_json) VALUES(?,?,?,'{}'::jsonb)",claim.execution().attemptId(),type,message);}
}
