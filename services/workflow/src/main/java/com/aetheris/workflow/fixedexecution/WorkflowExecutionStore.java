package com.aetheris.workflow.fixedexecution;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.service.*;
import com.aetheris.workflow.service.ExecutionClaimCoordinator.ExecutionClaim;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** All SQL source evidence is claimed and sealed under the current definition lock. */
@Service
public class WorkflowExecutionStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate transactions;
    private final ExecutionClaimCoordinator coordinator;private final TaskService tasks;
    public WorkflowExecutionStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ExecutionClaimCoordinator coordinator,TaskService tasks){
        this.jdbc=jdbc;this.transactions=new TransactionTemplate(manager);this.coordinator=coordinator;this.tasks=tasks;
    }
    public record Definition(long id,long revision,String definitionSha256,String canonicalDefinition,String approval,
        long scheduleGeneration,long bindingGeneration,String cronActor,Long cronRevision,String status,String cron){
        public boolean approved(){return approval!=null;}
        public boolean cronBound(){return approved()&&cronActor!=null&&cronRevision!=null&&cronRevision==revision&&"enabled".equals(status)&&cron!=null&&!cron.isBlank();}
    }
    public record Claim(ExecutionClaim execution,long revision,String manifestSha256,boolean dependenciesReady){
        public SqlExecutionClient.Submission submission(){return new SqlExecutionClient.Submission(execution.instance().getId(),execution.attemptId(),execution.fencingToken(),revision,manifestSha256);}
    }
    public record Lookup(String canonicalManifest,String manifestSha256,String status,long leaseExpiresAt) { }
    private static final String DEFINITION="""
        SELECT id,execution_revision,execution_definition_sha256,execution_definition_json::text definition,
          execution_approval::text approval,schedule_version,cron_binding_generation,cron_actor::text actor,
          cron_approved_execution_revision,status,cron_expression FROM workflow.task WHERE id=?
        """;
    public Definition definition(long id){return definition(id,false);}
    private Definition definition(long id,boolean lock){
        var rows=jdbc.query(DEFINITION+(lock?" FOR UPDATE":""),(r,i)->new Definition(r.getLong("id"),r.getLong("execution_revision"),
          r.getString("execution_definition_sha256"),r.getString("definition"),r.getString("approval"),r.getLong("schedule_version"),
          r.getLong("cron_binding_generation"),r.getString("actor"),(Long)r.getObject("cron_approved_execution_revision"),r.getString("status"),r.getString("cron_expression")),id);
        if(rows.isEmpty())throw new IllegalArgumentException("任务不存在");return rows.getFirst();
    }
    public JsonNode executableDefinition(Definition d){
        var n=FixedJson.parse(d.canonicalDefinition());
        FixedJson.fields(n,"schemaVersion","taskType","datasourceId","sql","timeoutSeconds","maxRetries","targetTable","jobDefinitionId","qualityRuleId","flinkEndpoint","flinkMode","eventTimeColumn","watermarkExpression","allowedLatenessSeconds","lateDataPolicy","deadLetterTable","dependencies");
        if(FixedJson.number(n,"schemaVersion",1)!=1||!"sql".equals(FixedJson.text(n,"taskType")))throw new IllegalArgumentException("本入口只支持固定SQL写任务");
        FixedJson.number(n,"datasourceId",1);String sql=FixedJson.text(n,"sql");if(sql.isBlank()||sql.length()>512*1024)throw FixedJson.invalid();
        if(!n.get("timeoutSeconds").isNull()&&FixedJson.number(n,"timeoutSeconds",1)>86400)throw FixedJson.invalid();
        var dependencies=n.get("dependencies");if(dependencies==null||!dependencies.isObject())throw FixedJson.invalid();
        if(!dependencies.isEmpty())FixedJson.fields(dependencies,"upstreamTaskIds");
        var ids=dependencies.isEmpty()?FixedJson.JSON.createArrayNode():dependencies.get("upstreamTaskIds");if(!ids.isArray())throw FixedJson.invalid();
        for(var id:ids){if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<=0)throw FixedJson.invalid();}
        return n;
    }
    public Definition approve(Definition expected,WorkflowActor actor){
        Objects.requireNonNull(actor);executableDefinition(expected);
        return transactions.execute(tx->{var current=definition(expected.id(),true);if(current.revision()!=expected.revision()||!current.definitionSha256().equals(expected.definitionSha256()))throw conflict();
          if(jdbc.update("""
              UPDATE workflow.task SET execution_approval=jsonb_build_object('uid',?::bigint,'username',?::text,'accessVersion',?::bigint,
                'revision',execution_revision,'definitionSha256',execution_definition_sha256),execution_approved_at=clock_timestamp(),
                cron_actor=NULL,cron_approved_execution_revision=NULL WHERE id=? AND execution_revision=? AND execution_definition_sha256=?
              """,actor.uid(),actor.username(),actor.accessVersion(),current.id(),expected.revision(),expected.definitionSha256())!=1)throw conflict();return definition(current.id());});
    }
    public Definition bindCron(long id,long expectedRevision,long generation,WorkflowActor actor){
        return transactions.execute(tx->{var d=definition(id,true);requireApproved(d);executableDefinition(d);
          if(d.revision()!=expectedRevision||d.scheduleGeneration()!=generation||!"enabled".equals(d.status())||d.cron()==null||d.cron().isBlank())throw conflict();
          if(jdbc.update("UPDATE workflow.task SET cron_actor=?::jsonb,cron_approved_execution_revision=execution_revision WHERE id=? AND execution_revision=? AND schedule_version=?",
             actor.forCron().json().toString(),id,expectedRevision,generation)!=1)throw conflict();return definition(id);});
    }
    public Claim claimManual(long id,String date,WorkflowActor actor){Objects.requireNonNull(actor);return transactions.execute(tx->claim(id,date,actor,null));}
    public Claim claimScheduled(long id,String date,long generation){return transactions.execute(tx->claim(id,date,null,generation));}
    private Claim claim(long id,String date,WorkflowActor actor,Long generation){
        com.aetheris.workflow.config.BusinessCalendar.parseExact(date);var d=definition(id,true);requireApproved(d);var definition=executableDefinition(d);
        if(!"enabled".equals(d.status()))throw conflict();
        if(generation!=null){if(generation!=d.scheduleGeneration()||!d.cronBound())throw conflict();actor=WorkflowActor.cron(FixedJson.parse(d.cronActor()));}
        var task=Objects.requireNonNull(tasks.getById(id));
        // Do not let a mapper/session cache replace the locked approved projection.
        task.setTaskType("sql");task.setDatasourceId(FixedJson.number(definition,"datasourceId",1));task.setTaskSql(FixedJson.text(definition,"sql"));
        task.setTimeoutSeconds(definition.get("timeoutSeconds").isNull()?1800:Math.toIntExact(FixedJson.number(definition,"timeoutSeconds",1)));
        task.setMaxRetries(definition.get("maxRetries").isNull()?0:Math.toIntExact(FixedJson.number(definition,"maxRetries",0)));
        int timeout=task.getTimeoutSeconds();
        var execution=coordinator.claimFixedSql(task,date,generation==null?"manual":"schedule",actor.username(),timeout);
        // Dependencies are the frozen definition edges, never an unversioned caller closure.
        boolean ready=true;var edges=definition.get("dependencies").isEmpty()?FixedJson.JSON.createArrayNode():definition.get("dependencies").get("upstreamTaskIds");for(var upstream:edges){
          if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow.task_instance WHERE task_id=? AND biz_date=? AND status='success')",Boolean.class,upstream.longValue(),date)))ready=false;
        }
        String actorJson=actor.json().toString();var sealed=jdbc.queryForMap("""
            WITH manifest AS (SELECT jsonb_build_object('schemaVersion',1,'origin','workflow','operation','metadata.fixed-sql.submit',
              'resourceId',id,'runId',?::bigint,'attemptId',?::bigint,'fence',?::bigint,'executionRevision',execution_revision,
              'definitionSha256',execution_definition_sha256,'actor',?::jsonb,'approval',execution_approval,
              'execution',execution_definition_json,'parameters',jsonb_build_object('bizDate',?::text),'parent','null'::jsonb) body
              FROM workflow.task WHERE id=?)
            UPDATE workflow.execution_attempt attempt SET execution_revision=?,invocation_json=?::jsonb,approval_json=?::jsonb,source_schedule_generation=?,source_binding_generation=?,
              sealed_manifest=manifest.body,manifest_sha256=encode(sha256(convert_to(manifest.body::text,'UTF8')),'hex'),manifest_sealed_at=clock_timestamp()
            FROM manifest WHERE attempt.id=? AND attempt.instance_id=? AND attempt.fencing_token=?
              AND attempt.status='running' AND attempt.worker_id IS NOT NULL AND attempt.lease_expires_at>clock_timestamp()
            RETURNING attempt.manifest_sha256
            """,execution.instance().getId(),execution.attemptId(),execution.fencingToken(),actorJson,date,id,d.revision(),actorJson,d.approval(),generation==null?null:d.scheduleGeneration(),generation==null?null:d.bindingGeneration(),execution.attemptId(),execution.instance().getId(),execution.fencingToken());
        return new Claim(execution,d.revision(),(String)sealed.get("manifest_sha256"),ready);
    }
    public void bindReceipt(Claim claim,long receipt){
        var e=claim.execution();if(receipt<=0||jdbc.update("""
          UPDATE workflow.execution_attempt a SET metadata_receipt_id=? FROM workflow.task_instance i
          WHERE a.id=? AND a.instance_id=i.id AND i.active_attempt_id=a.id AND i.fencing_token=a.fencing_token
            AND a.fencing_token=? AND a.status='running' AND a.manifest_sealed_at IS NOT NULL
            AND a.lease_expires_at>clock_timestamp() AND i.lease_expires_at>clock_timestamp()
            AND (a.metadata_receipt_id IS NULL OR a.metadata_receipt_id=?)
          """,receipt,e.attemptId(),e.fencingToken(),receipt)!=1)throw conflict();
    }
    public Lookup lookup(long instance,long attempt,long fence){
        if(instance<=0||attempt<=0||fence<=0)throw FixedJson.invalid();
        var rows=jdbc.query("""
          SELECT a.sealed_manifest::text canonical,a.manifest_sha256,a.status,
            floor(extract(epoch FROM least(a.lease_expires_at,i.lease_expires_at)))::bigint lease
          FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.id=a.instance_id JOIN workflow.task t ON t.id=i.task_id
          WHERE i.id=? AND a.id=? AND a.fencing_token=? AND i.active_attempt_id=a.id AND i.fencing_token=a.fencing_token
            AND a.status='running' AND i.status='running' AND a.worker_id IS NOT NULL AND length(a.worker_id)>0
            AND a.lease_expires_at>clock_timestamp() AND i.lease_expires_at>clock_timestamp() AND a.manifest_sealed_at IS NOT NULL
            AND t.task_type='sql' AND t.status='enabled' AND t.execution_revision=a.execution_revision
            AND t.execution_approval=a.approval_json AND t.execution_approval IS NOT NULL
            AND a.manifest_sha256=encode(sha256(convert_to(a.sealed_manifest::text,'UTF8')),'hex')
            AND a.sealed_manifest=jsonb_build_object('schemaVersion',1,'origin','workflow','operation','metadata.fixed-sql.submit',
              'resourceId',t.id,'runId',i.id,'attemptId',a.id,'fence',a.fencing_token,'executionRevision',a.execution_revision,
              'definitionSha256',t.execution_definition_sha256,'actor',a.invocation_json,'approval',a.approval_json,
              'execution',t.execution_definition_json,'parameters',a.parameters_json,'parent','null'::jsonb)
            AND a.parameters_json=jsonb_build_object('bizDate',i.biz_date)
            AND ((a.invocation_json->>'kind'='manual' AND a.source_schedule_generation IS NULL AND a.source_binding_generation IS NULL)
              OR (a.invocation_json->>'kind'='cron' AND a.invocation_json=t.cron_actor AND t.cron_approved_execution_revision=a.execution_revision
                AND a.source_schedule_generation=t.schedule_version AND a.source_binding_generation=t.cron_binding_generation))
          """,(r,index)->new Lookup(r.getString("canonical"),r.getString("manifest_sha256"),r.getString("status"),r.getLong("lease")),instance,attempt,fence);
        if(rows.size()!=1)throw conflict();return rows.getFirst();
    }
    private void requireApproved(Definition d){
        if(!d.approved())throw new IllegalStateException("当前执行定义尚未明确批准");var n=FixedJson.parse(d.approval());FixedJson.fields(n,"uid","username","accessVersion","revision","definitionSha256");
        FixedJson.number(n,"uid",1);FixedJson.text(n,"username");FixedJson.number(n,"accessVersion",0);
        if(FixedJson.number(n,"revision",1)!=d.revision()||!FixedJson.hash(n,"definitionSha256").equals(d.definitionSha256()))throw conflict();
    }
    public static IllegalStateException conflict(){return new IllegalStateException("执行版本、调度绑定或租约已变化，请重新确认");}
}
