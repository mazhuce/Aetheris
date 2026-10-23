package com.aetheris.ingestion.fixedexecution;

import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Short transactions own approval/CAS/claim/seal. No remote HTTP is performed under a database lock. */
@Service
public class IngestionExecutionStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate transactions;
    private final ExecutionRecoveryService recovery;private final IngestionRunService runs;
    public IngestionExecutionStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ExecutionRecoveryService recovery,IngestionRunService runs){
        this.jdbc=jdbc;this.transactions=new TransactionTemplate(manager);this.recovery=Objects.requireNonNull(recovery);this.runs=runs;
    }
    public record Definition(long id,long revision,String definitionSha256,String canonicalDefinition,String approval,
                             long scheduleGeneration,long bindingGeneration,String cronActor,Long cronRevision,String status,String cron) {
        public boolean approved(){return approval!=null;}
        public boolean cronBound(){return approved()&&cronActor!=null&&cronRevision!=null&&cronRevision==revision&&"enabled".equals(status)&&cron!=null&&!cron.isBlank();}
    }
    public record Claim(IngestionRun run,long revision,String manifestSha256,boolean reused) {
        public MetadataIngestionClient.Submission submission(){return new MetadataIngestionClient.Submission(run.getId(),revision,manifestSha256);}
    }
    public record Lookup(String canonicalManifest,String manifestSha256,String status,long leaseExpiresAt) { }
    private static final String DEFINITION="""
        SELECT id,execution_revision,execution_definition_sha256,execution_definition_json::text definition,
            execution_approval::text approval,schedule_version,cron_binding_generation,cron_actor::text actor,
            cron_approved_execution_revision,status,schedule_cron
        FROM ingestion.ingestion_task WHERE id=?
        """;
    public Definition definition(long id){return definition(id,false);}
    private Definition definition(long id,boolean lock) {
        var rows=jdbc.query(DEFINITION+(lock?" FOR UPDATE":""),(r,index)->new Definition(r.getLong("id"),r.getLong("execution_revision"),
                r.getString("execution_definition_sha256"),r.getString("definition"),r.getString("approval"),r.getLong("schedule_version"),
                r.getLong("cron_binding_generation"),r.getString("actor"),(Long)r.getObject("cron_approved_execution_revision"),r.getString("status"),r.getString("schedule_cron")),id);
        if(rows.isEmpty())throw new IllegalArgumentException("接入任务不存在");return rows.getFirst();
    }
    /** Validate only the actual DB projection, never an HTTP definition copy. */
    public JsonNode executableDefinition(Definition definition) {
        JsonNode n=FixedJson.parse(definition.canonicalDefinition());
        FixedJson.fields(n,"schemaVersion","sourceDatasourceId","targetDatasourceId","sourceSql","targetTable","writeMode","batchSize");
        if(FixedJson.number(n,"schemaVersion",1)!=1 || FixedJson.text(n,"sourceSql").isBlank() || FixedJson.text(n,"targetTable").isBlank()
                || !Set.of("append","replace").contains(FixedJson.text(n,"writeMode")) || FixedJson.number(n,"batchSize",1)>5000)throw FixedJson.invalid();
        FixedJson.number(n,"sourceDatasourceId",1);FixedJson.number(n,"targetDatasourceId",1);return n;
    }
    public Definition approve(Definition expected,IngestionActor actor) {
        executableDefinition(expected);
        return transactions.execute(tx->{var current=definition(expected.id(),true);sameRevision(current,expected);
            int updated=jdbc.update("""
                UPDATE ingestion.ingestion_task SET execution_approval=jsonb_build_object('uid',?::bigint,'username',?::text,
                    'accessVersion',?::bigint,'revision',execution_revision,'definitionSha256',execution_definition_sha256),
                    execution_approved_at=clock_timestamp(),cron_actor=NULL,cron_approved_execution_revision=NULL
                WHERE id=? AND execution_revision=? AND execution_definition_sha256=?
                """,actor.uid(),actor.username(),actor.accessVersion(),current.id(),expected.revision(),expected.definitionSha256());
            if(updated!=1)throw conflict();return definition(current.id());});
    }
    public Definition bindCron(long id,long expectedRevision,long expectedGeneration,IngestionActor actor) {
        return transactions.execute(tx->{var current=definition(id,true);requireApproved(current);
            if(current.revision()!=expectedRevision || current.scheduleGeneration()!=expectedGeneration || !"enabled".equals(current.status()) || current.cron()==null || current.cron().isBlank())throw conflict();
            int updated=jdbc.update("""
                UPDATE ingestion.ingestion_task SET cron_actor=?::jsonb,cron_approved_execution_revision=execution_revision
                WHERE id=? AND execution_revision=? AND schedule_version=? AND execution_approval IS NOT NULL
                """,actor.forCron().json().toString(),id,expectedRevision,expectedGeneration);
            if(updated!=1)throw conflict();return definition(id);});
    }
    public Claim claimManual(long taskId,IngestionActor actor,String key,Long replayOf) {
        if(actor==null||!"manual".equals(actor.json().path("kind").asText()))throw new IllegalStateException("手动执行需要可信人工主体");
        try{return transactions.execute(tx->claim(taskId,actor,key,replayOf,null));}
        catch(org.springframework.dao.DataIntegrityViolationException concurrent){throw conflict();}
    }
    public Claim claimScheduled(long taskId,long generation,String key) {
        try{return transactions.execute(tx->claim(taskId,null,key,null,generation));}
        catch(org.springframework.dao.DataIntegrityViolationException concurrent){throw conflict();}
    }
    private Claim claim(long taskId,IngestionActor actor,String key,Long replayOf,Long generation) {
        if(key!=null && (key.isBlank()||key.length()>128))throw new IllegalArgumentException("幂等键格式无效");
        Definition current=definition(taskId,true);requireApproved(current);executableDefinition(current);
        if(!"enabled".equals(current.status()))throw conflict();
        if(generation!=null) {
            if(current.scheduleGeneration()!=generation || !current.cronBound())throw conflict();
            actor=IngestionActor.cron(FixedJson.parse(current.cronActor()));
        }
        if(key!=null) {
            var existing=jdbc.queryForList("SELECT id,status,execution_revision,manifest_sha256 FROM ingestion.ingestion_run WHERE task_id=? AND request_key=?",taskId,key);
            if(!existing.isEmpty()) {
                var old=existing.getFirst();if("running".equals(old.get("status")))throw conflict();
                if(old.get("execution_revision")==null || ((Number)old.get("execution_revision")).longValue()!=current.revision())throw conflict();
                return new Claim(runs.getById(((Number)old.get("id")).longValue()),current.revision(),(String)old.get("manifest_sha256"),true);
            }
        }
        if(replayOf!=null) {
            var prior=jdbc.queryForList("SELECT task_id,status,execution_revision FROM ingestion.ingestion_run WHERE id=? FOR SHARE",replayOf);
            if(prior.isEmpty() || ((Number)prior.getFirst().get("task_id")).longValue()!=taskId || !"failed".equals(prior.getFirst().get("status"))
                    || prior.getFirst().get("execution_revision")==null || ((Number)prior.getFirst().get("execution_revision")).longValue()!=current.revision())throw conflict();
        }
        String actorJson=actor.json().toString();
        Long id=jdbc.queryForObject("""
            INSERT INTO ingestion.ingestion_run(task_id,status,request_key,replay_of_run_id,triggered_by,execution_snapshot)
            VALUES(?,'running',?,?,?,?) RETURNING id
            """,Long.class,taskId,key,replayOf,actor.username(),current.canonicalDefinition());
        recovery.claim(id);
        var sealed=jdbc.queryForMap("""
            WITH manifest AS (
                SELECT jsonb_build_object('schemaVersion',1,'origin','ingestion','operation','metadata.fixed-transfer.submit',
                    'resourceId',id,'runId',?::bigint,'attemptId',0,'fence',1,'executionRevision',execution_revision,
                    'definitionSha256',execution_definition_sha256,'actor',?::jsonb,'approval',execution_approval,
                    'execution',execution_definition_json,'parameters','{}'::jsonb,'parent','null'::jsonb) body,
                    schedule_version,cron_binding_generation
                FROM ingestion.ingestion_task WHERE id=?
            ) UPDATE ingestion.ingestion_run run SET execution_revision=?,invocation_json=?::jsonb,approval_json=?::jsonb,
                sealed_manifest=manifest.body,manifest_sha256=encode(sha256(convert_to(manifest.body::text,'UTF8')),'hex'),
                manifest_sealed_at=clock_timestamp(),
                source_schedule_generation=CASE WHEN manifest.body->'actor'->>'kind'='cron' THEN manifest.schedule_version ELSE NULL END,
                source_binding_generation=CASE WHEN manifest.body->'actor'->>'kind'='cron' THEN manifest.cron_binding_generation ELSE NULL END
            FROM manifest WHERE run.id=? AND run.status='running' AND run.lease_owner IS NOT NULL AND run.lease_expires_at>clock_timestamp()
            RETURNING run.manifest_sha256
            """,id,actorJson,taskId,current.revision(),actorJson,current.approval(),id);
        return new Claim(runs.getById(id),current.revision(),(String)sealed.get("manifest_sha256"),false);
    }
    public void bindReceipt(long runId,long receiptId) {
        if(receiptId<=0 || jdbc.update("""
            UPDATE ingestion.ingestion_run SET metadata_receipt_id=? WHERE id=? AND manifest_sealed_at IS NOT NULL
              AND (metadata_receipt_id IS NULL OR metadata_receipt_id=?)
            """,receiptId,runId,receiptId)!=1)throw conflict();
    }
    public Lookup lookup(long runId,long fence) {
        if(runId<=0||fence!=1)throw FixedJson.invalid();
        var rows=jdbc.query("""
            SELECT run.sealed_manifest::text canonical,run.manifest_sha256,run.status,
                floor(extract(epoch FROM run.lease_expires_at))::bigint lease
            FROM ingestion.ingestion_run run JOIN ingestion.ingestion_task task ON task.id=run.task_id
            WHERE run.id=? AND run.execution_fence=? AND run.manifest_sealed_at IS NOT NULL
              AND run.status='running' AND run.lease_owner IS NOT NULL AND run.lease_expires_at>clock_timestamp()
              AND task.status='enabled' AND task.execution_approval IS NOT NULL
              AND task.execution_revision=run.execution_revision AND task.execution_approval=run.approval_json
              AND run.execution_snapshot::jsonb=task.execution_definition_json
              AND run.manifest_sha256=encode(sha256(convert_to(run.sealed_manifest::text,'UTF8')),'hex')
              AND run.sealed_manifest=jsonb_build_object('schemaVersion',1,'origin','ingestion','operation','metadata.fixed-transfer.submit',
                  'resourceId',task.id,'runId',run.id,'attemptId',0,'fence',run.execution_fence,'executionRevision',run.execution_revision,
                  'definitionSha256',task.execution_definition_sha256,'actor',run.invocation_json,'approval',run.approval_json,
                  'execution',task.execution_definition_json,'parameters','{}'::jsonb,'parent','null'::jsonb)
              AND ((run.invocation_json->>'kind'='manual' AND run.source_schedule_generation IS NULL AND run.source_binding_generation IS NULL)
                  OR (run.invocation_json->>'kind'='cron' AND run.source_schedule_generation=task.schedule_version
                  AND run.source_binding_generation=task.cron_binding_generation
                  AND run.invocation_json=task.cron_actor AND task.cron_approved_execution_revision=run.execution_revision))
            """,(r,index)->new Lookup(r.getString("canonical"),r.getString("manifest_sha256"),r.getString("status"),r.getLong("lease")),runId,fence);
        if(rows.isEmpty())throw conflict();return rows.getFirst();
    }
    private void requireApproved(Definition definition) {
        if(!definition.approved())throw new IllegalStateException("当前执行定义尚未明确批准");
        var approval=FixedJson.parse(definition.approval());FixedJson.fields(approval,"uid","username","accessVersion","revision","definitionSha256");
        FixedJson.number(approval,"uid",1);FixedJson.text(approval,"username");FixedJson.number(approval,"accessVersion",0);
        if(FixedJson.number(approval,"revision",1)!=definition.revision() || !FixedJson.hash(approval,"definitionSha256").equals(definition.definitionSha256()))throw conflict();
    }
    private static void sameRevision(Definition current,Definition expected){if(current.revision()!=expected.revision()||!current.definitionSha256().equals(expected.definitionSha256()))throw conflict();}
    public static IllegalStateException conflict(){return new IllegalStateException("执行版本、调度绑定或租约已失效，请重新确认");}
}
