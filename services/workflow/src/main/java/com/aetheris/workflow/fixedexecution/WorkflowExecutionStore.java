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
    private WorkflowEngineMaterial engineMaterial;
    @org.springframework.beans.factory.annotation.Autowired public void installEngineMaterial(WorkflowEngineMaterial material){this.engineMaterial=Objects.requireNonNull(material);}
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
    Definition lockDefinition(long id){return definition(id,true);}
    private Definition definition(long id,boolean lock){
        var rows=jdbc.query(DEFINITION+(lock?" FOR UPDATE":""),(r,i)->new Definition(r.getLong("id"),r.getLong("execution_revision"),
          r.getString("execution_definition_sha256"),r.getString("definition"),r.getString("approval"),r.getLong("schedule_version"),
          r.getLong("cron_binding_generation"),r.getString("actor"),(Long)r.getObject("cron_approved_execution_revision"),r.getString("status"),r.getString("cron_expression")),id);
        if(rows.isEmpty())throw new IllegalArgumentException("任务不存在");return rows.getFirst();
    }
    public JsonNode executableDefinition(Definition d){
        var n=FixedJson.parse(d.canonicalDefinition());
        if("quality".equals(n.path("taskType").asText())){com.aetheris.security.protocol.execution.WorkflowQualityParent.validateDefinition(n);return n;}
        if(Set.of("spark","flink").contains(n.path("taskType").asText())){
            FixedJson.fields(n,"schemaVersion","taskType","datasourceId","sql","timeoutSeconds","maxRetries","targetTable","jobDefinitionId","qualityRuleId","flinkEndpoint","flinkMode","eventTimeColumn","watermarkExpression","allowedLatenessSeconds","lateDataPolicy","deadLetterTable","dependencies","engineDefinition");
            if(FixedJson.number(n,"schemaVersion",1)!=1)throw FixedJson.invalid();
            WorkflowEngineMaterial.validate(n.get("engineDefinition"),FixedJson.text(n,"taskType"));
            if(!n.get("timeoutSeconds").isNull()&&FixedJson.number(n,"timeoutSeconds",1)>86400)throw FixedJson.invalid();
            validateDependencies(n);return n;
        }
        FixedJson.fields(n,"schemaVersion","taskType","datasourceId","sql","timeoutSeconds","maxRetries","targetTable","jobDefinitionId","qualityRuleId","flinkEndpoint","flinkMode","eventTimeColumn","watermarkExpression","allowedLatenessSeconds","lateDataPolicy","deadLetterTable","dependencies");
        if(FixedJson.number(n,"schemaVersion",1)!=1||!"sql".equals(FixedJson.text(n,"taskType")))throw new IllegalArgumentException("本入口只支持固定SQL写任务");
        FixedJson.number(n,"datasourceId",1);String sql=FixedJson.text(n,"sql");if(sql.isBlank()||sql.length()>512*1024)throw FixedJson.invalid();
        if(!n.get("timeoutSeconds").isNull()&&FixedJson.number(n,"timeoutSeconds",1)>86400)throw FixedJson.invalid();
        validateDependencies(n);return n;
    }
    private void validateDependencies(JsonNode n){
        var dependencies=n.get("dependencies");if(dependencies==null||!dependencies.isObject())throw FixedJson.invalid();
        if(!dependencies.isEmpty())FixedJson.fields(dependencies,"upstreamTaskIds");
        var ids=dependencies.isEmpty()?FixedJson.JSON.createArrayNode():dependencies.get("upstreamTaskIds");if(!ids.isArray())throw FixedJson.invalid();
        for(var id:ids){if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<=0)throw FixedJson.invalid();}
    }
    public Definition approveEngine(Definition expected,WorkflowActor actor){
        Objects.requireNonNull(actor);return transactions.execute(tx->{var current=definition(expected.id(),true);
            if(current.revision()!=expected.revision()||!current.definitionSha256().equals(expected.definitionSha256()))throw conflict();
            if(engineMaterial==null)throw new IllegalStateException("引擎材料捕获器未配置");
            var material=engineMaterial.current(current);
            jdbc.update("UPDATE workflow.task SET engine_definition_json=?::jsonb WHERE id=?",material.canonical(),current.id());
            return approve(definition(current.id(),true),actor);});
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
    public Definition approveQuality(Definition expected,WorkflowActor actor,long ruleRevision,String ruleHash){
        if(ruleRevision<=0||ruleHash==null||!ruleHash.matches("[a-f0-9]{64}"))throw FixedJson.invalid();
        return transactions.execute(tx->{var current=definition(expected.id(),true);
            if(current.revision()!=expected.revision()||!current.definitionSha256().equals(expected.definitionSha256())||!"quality".equals(FixedJson.parse(current.canonicalDefinition()).path("taskType").asText()))throw conflict();
            jdbc.update("UPDATE workflow.task SET quality_execution_revision=?,quality_definition_sha256=? WHERE id=?",ruleRevision,ruleHash,current.id());
            return approve(definition(current.id(),true),actor);});
    }
    public Definition bindCron(long id,long expectedRevision,long generation,WorkflowActor actor){
        return transactions.execute(tx->{var d=definition(id,true);requireApproved(d);executableDefinition(d);
          if(d.revision()!=expectedRevision||d.scheduleGeneration()!=generation||!"enabled".equals(d.status())||d.cron()==null||d.cron().isBlank())throw conflict();
          if(jdbc.update("UPDATE workflow.task SET cron_actor=?::jsonb,cron_approved_execution_revision=execution_revision WHERE id=? AND execution_revision=? AND schedule_version=?",
             actor.forCron().json().toString(),id,expectedRevision,generation)!=1)throw conflict();return definition(id);});
    }
    public record PipelineBinding(long runId,long nodeId,long runFence){}
    public Claim claimManual(long id,String date,WorkflowActor actor){Objects.requireNonNull(actor);return transactions.execute(tx->claim(id,date,actor,null,null));}
    public Claim claimScheduled(long id,String date,long generation){return transactions.execute(tx->claim(id,date,null,generation,null));}
    Claim claimPipeline(long id,String date,WorkflowActor actor,PipelineBinding pipeline){return transactions.execute(tx->claim(id,date,Objects.requireNonNull(actor),null,Objects.requireNonNull(pipeline)));}
    private Claim claim(long id,String date,WorkflowActor actor,Long generation,PipelineBinding pipeline){
        com.aetheris.workflow.config.BusinessCalendar.parseExact(date);var d=definition(id,true);requireApproved(d);var definition=executableDefinition(d);
        if(!"enabled".equals(d.status()))throw conflict();
        String type=FixedJson.text(definition,"taskType");boolean engine=Set.of("spark","flink").contains(type);
        if(engine){if(engineMaterial==null)throw new IllegalStateException("引擎材料捕获器未配置");engineMaterial.requireCurrent(d);}
        if(generation!=null){if(generation!=d.scheduleGeneration()||!d.cronBound())throw conflict();actor=WorkflowActor.cron(FixedJson.parse(d.cronActor()));}
        var task=Objects.requireNonNull(tasks.getById(id));
        // Do not let a mapper/session cache replace the locked approved projection.
        boolean quality="quality".equals(type);
        task.setTaskType(type);task.setDatasourceId("sql".equals(type)?FixedJson.number(definition,"datasourceId",1):null);task.setTaskSql(definition.get("sql").isNull()?null:FixedJson.text(definition,"sql"));
        task.setQualityRuleId(quality?FixedJson.number(definition,"qualityRuleId",1):null);
        task.setJobDefinitionId("spark".equals(type)?FixedJson.number(definition,"jobDefinitionId",1):null);
        task.setFlinkEndpoint("flink".equals(type)?FixedJson.text(definition.get("engineDefinition"),"gatewayOrigin"):null);
        task.setFlinkMode("flink".equals(type)?FixedJson.text(definition,"flinkMode"):null);
        task.setEventTimeColumn(definition.get("eventTimeColumn").isNull()?null:FixedJson.text(definition,"eventTimeColumn"));
        task.setWatermarkExpression(definition.get("watermarkExpression").isNull()?null:FixedJson.text(definition,"watermarkExpression"));
        task.setAllowedLatenessSeconds(definition.get("allowedLatenessSeconds").isNull()?null:Math.toIntExact(FixedJson.number(definition,"allowedLatenessSeconds",0)));
        task.setLateDataPolicy(definition.get("lateDataPolicy").isNull()?null:FixedJson.text(definition,"lateDataPolicy"));
        task.setDeadLetterTable(definition.get("deadLetterTable").isNull()?null:FixedJson.text(definition,"deadLetterTable"));
        task.setTimeoutSeconds(definition.get("timeoutSeconds").isNull()?1800:Math.toIntExact(FixedJson.number(definition,"timeoutSeconds",1)));
        task.setMaxRetries(definition.get("maxRetries").isNull()?0:Math.toIntExact(FixedJson.number(definition,"maxRetries",0)));
        int timeout=task.getTimeoutSeconds();
        String trigger=pipeline!=null?"pipeline":generation==null?"manual":"schedule";
        var execution=engine?coordinator.claimFixedEngine(task,date,trigger,actor.username(),timeout):quality?coordinator.claimFixedQuality(task,date,trigger,actor.username(),timeout):coordinator.claimFixedSql(task,date,trigger,actor.username(),timeout);
        if("spark".equals(type)){
            String material=definition.get("engineDefinition").toString();
            if(jdbc.update("""
                UPDATE workflow.execution_attempt SET job_version=(?::jsonb->>'jobVersion')::int,artifact_digest=?::jsonb->>'artifactSha256',
                  definition_digest=?,source_repository_id=(?::jsonb->'source'->>'repositoryId')::bigint,
                  source_commit_sha=?::jsonb->'source'->>'commitSha',source_path=?::jsonb->'source'->>'path',
                  source_blob_id=?::jsonb->'source'->>'blobId',source_blob_sha256=?::jsonb->'source'->>'blobSha256',
                  source_signature_status=?::jsonb->'source'->>'signatureStatus',source_signer_fingerprint=?::jsonb->'source'->>'signerFingerprint',
                  source_verified_at=(?::jsonb->'source'->>'verifiedAt')::timestamptz,
                  input_partitions_json=jsonb_build_object('tables',?::jsonb->'inputTables','bizDate',?::text),
                  output_partitions_json=jsonb_build_object('uri',(?::jsonb->>'outputUri')||'/biz_date='||?::text,'writeMode',?::jsonb->>'writeMode')
                WHERE id=? AND fencing_token=? AND status='queued' AND manifest_sealed_at IS NULL
                """,material,material,d.definitionSha256(),material,material,material,material,material,material,material,material,material,date,material,date,material,execution.attemptId(),execution.fencingToken())!=1)throw conflict();
        }
        if(pipeline!=null&&jdbc.update("""
            UPDATE workflow.pipeline_node_run SET instance_id=?,attempt_id=?,fencing_token=?,started_at=COALESCE(started_at,clock_timestamp()),status='running'
            WHERE id=? AND pipeline_run_id=? AND task_id=? AND status IN ('queued','running') AND attempt_id IS NULL
            """,execution.instance().getId(),execution.attemptId(),execution.fencingToken(),pipeline.nodeId(),pipeline.runId(),id)!=1)throw conflict();
        // Dependencies are the frozen definition edges, never an unversioned caller closure.
        boolean ready=true;var edges=definition.get("dependencies").isEmpty()?FixedJson.JSON.createArrayNode():definition.get("dependencies").get("upstreamTaskIds");for(var upstream:edges){
          if(pipeline!=null)continue; // PipelineExecutionStore already requires exact frozen node/attempt evidence.
          if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow.task_instance WHERE task_id=? AND biz_date=? AND status='success')",Boolean.class,upstream.longValue(),date)))ready=false;
        }
        String actorJson=actor.json().toString();var sealed=jdbc.queryForMap("""
            WITH manifest AS (SELECT jsonb_build_object('schemaVersion',1,'origin','workflow','operation',CASE task_type WHEN 'quality' THEN 'quality.workflow-child.submit' WHEN 'spark' THEN 'workflow.fixed-spark.dispatch' WHEN 'flink' THEN 'workflow.fixed-flink.dispatch' ELSE 'metadata.fixed-sql.submit' END,
              'resourceId',id,'runId',?::bigint,'attemptId',?::bigint,'fence',?::bigint,'executionRevision',execution_revision,
              'definitionSha256',execution_definition_sha256,'actor',?::jsonb,'approval',execution_approval,
              'execution',execution_definition_json,'parameters',jsonb_build_object('bizDate',?::text),'parent','null'::jsonb) body
              FROM workflow.task WHERE id=?)
            UPDATE workflow.execution_attempt attempt SET execution_revision=?,invocation_json=?::jsonb,approval_json=?::jsonb,source_schedule_generation=?,source_binding_generation=?,
              pipeline_run_id=?,pipeline_node_id=?,pipeline_fence=?,
              sealed_manifest=manifest.body,manifest_sha256=encode(sha256(convert_to(manifest.body::text,'UTF8')),'hex'),manifest_sealed_at=clock_timestamp()
            FROM manifest WHERE attempt.id=? AND attempt.instance_id=? AND attempt.fencing_token=?
              AND attempt.status IN ('queued','running') AND attempt.worker_id IS NOT NULL AND attempt.lease_expires_at>clock_timestamp()
            RETURNING attempt.manifest_sha256
            """,execution.instance().getId(),execution.attemptId(),execution.fencingToken(),actorJson,date,id,d.revision(),actorJson,d.approval(),generation==null?null:d.scheduleGeneration(),generation==null?null:d.bindingGeneration(),pipeline==null?null:pipeline.runId(),pipeline==null?null:pipeline.nodeId(),pipeline==null?null:pipeline.runFence(),execution.attemptId(),execution.instance().getId(),execution.fencingToken());
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
    public String taskType(Claim claim){var e=claim.execution();return jdbc.queryForObject("SELECT task_type FROM workflow.execution_attempt WHERE id=? AND instance_id=? AND fencing_token=? AND manifest_sha256=? AND manifest_sealed_at IS NOT NULL",String.class,e.attemptId(),e.instance().getId(),e.fencingToken(),claim.manifestSha256());}
    public Lookup lookup(long instance,long attempt,long fence){
        return lookup(instance,attempt,fence,"sql");
    }
    public Lookup qualityParent(com.aetheris.security.protocol.execution.WorkflowQualityParent.Reference ref){
        var found=lookup(ref.instanceId(),ref.attemptId(),ref.fence(),"quality");
        com.aetheris.security.protocol.execution.WorkflowQualityParent.parse(found.canonicalManifest(),found.manifestSha256(),ref);return found;
    }
    private Lookup lookup(long instance,long attempt,long fence,String type){
        if(instance<=0||attempt<=0||fence<=0)throw FixedJson.invalid();
        var rows=jdbc.query("""
          SELECT a.sealed_manifest::text canonical,a.manifest_sha256,a.status,
            floor(extract(epoch FROM least(a.lease_expires_at,i.lease_expires_at,
              (SELECT p.lease_expires_at FROM workflow.pipeline_run p WHERE p.id=a.pipeline_run_id))))::bigint lease
          FROM workflow.execution_attempt a JOIN workflow.task_instance i ON i.id=a.instance_id JOIN workflow.task t ON t.id=i.task_id
          WHERE i.id=? AND a.id=? AND a.fencing_token=? AND i.active_attempt_id=a.id AND i.fencing_token=a.fencing_token
            AND a.status='running' AND i.status='running' AND a.worker_id IS NOT NULL AND length(a.worker_id)>0
            AND a.lease_expires_at>clock_timestamp() AND i.lease_expires_at>clock_timestamp() AND a.manifest_sealed_at IS NOT NULL
            AND t.task_type=? AND t.status='enabled' AND t.execution_revision=a.execution_revision
            AND t.execution_approval=a.approval_json AND t.execution_approval IS NOT NULL
            AND a.manifest_sha256=encode(sha256(convert_to(a.sealed_manifest::text,'UTF8')),'hex')
            AND a.sealed_manifest=jsonb_build_object('schemaVersion',1,'origin','workflow','operation',CASE t.task_type WHEN 'quality' THEN 'quality.workflow-child.submit' ELSE 'metadata.fixed-sql.submit' END,
              'resourceId',t.id,'runId',i.id,'attemptId',a.id,'fence',a.fencing_token,'executionRevision',a.execution_revision,
              'definitionSha256',t.execution_definition_sha256,'actor',a.invocation_json,'approval',a.approval_json,
              'execution',t.execution_definition_json,'parameters',a.parameters_json,'parent','null'::jsonb)
            AND a.parameters_json=jsonb_build_object('bizDate',i.biz_date)
            AND ((a.invocation_json->>'kind'='manual' AND a.pipeline_run_id IS NULL AND a.pipeline_node_id IS NULL AND a.pipeline_fence IS NULL AND a.source_schedule_generation IS NULL AND a.source_binding_generation IS NULL)
              OR (a.invocation_json->>'kind'='cron' AND a.invocation_json=t.cron_actor AND t.cron_approved_execution_revision=a.execution_revision
                AND a.pipeline_run_id IS NULL AND a.pipeline_node_id IS NULL AND a.pipeline_fence IS NULL
                AND a.source_schedule_generation=t.schedule_version AND a.source_binding_generation=t.cron_binding_generation)
              OR (a.invocation_json->>'kind'='pipeline' AND workflow.pipeline_binding_valid(a)))
          """,(r,index)->new Lookup(r.getString("canonical"),r.getString("manifest_sha256"),r.getString("status"),r.getLong("lease")),instance,attempt,fence,type);
        if(rows.size()!=1)throw conflict();return rows.getFirst();
    }
    private void requireApproved(Definition d){
        if(!d.approved())throw new IllegalStateException("当前执行定义尚未明确批准");var n=FixedJson.parse(d.approval());FixedJson.fields(n,"uid","username","accessVersion","revision","definitionSha256");
        FixedJson.number(n,"uid",1);FixedJson.text(n,"username");FixedJson.number(n,"accessVersion",0);
        if(FixedJson.number(n,"revision",1)!=d.revision()||!FixedJson.hash(n,"definitionSha256").equals(d.definitionSha256()))throw conflict();
    }
    public static IllegalStateException conflict(){return new IllegalStateException("执行版本、调度绑定或租约已变化，请重新确认");}
}
