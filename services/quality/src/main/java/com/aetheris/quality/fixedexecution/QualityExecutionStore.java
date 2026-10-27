package com.aetheris.quality.fixedexecution;

import com.aetheris.quality.entity.QualityCheckResult;
import com.aetheris.quality.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Short transactions own approval/CAS/claim/seal. No remote HTTP is performed under a database lock. */
@Service
public class QualityExecutionStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate transactions;
    private final ExecutionRecoveryService recovery;private final QualityCheckResultService results;
    public QualityExecutionStore(JdbcTemplate jdbc,PlatformTransactionManager manager,ExecutionRecoveryService recovery,QualityCheckResultService results){
        this.jdbc=jdbc;this.transactions=new TransactionTemplate(manager);this.recovery=Objects.requireNonNull(recovery);this.results=Objects.requireNonNull(results);
    }
    public record Definition(long id,long revision,String definitionSha256,String canonicalDefinition,String approval,
                             long scheduleGeneration,long bindingGeneration,String cronActor,Long cronRevision,String status,String cron) {
        public boolean approved(){return approval!=null;}
        public boolean cronBound(){return approved()&&cronActor!=null&&cronRevision!=null&&cronRevision==revision&&"enabled".equals(status)&&cron!=null&&!cron.isBlank();}
    }
    public record Claim(QualityCheckResult result,long revision,String manifestSha256,long threshold,String targetTable,boolean reused) {
        public QualityQueryClient.Submission submission(){return new QualityQueryClient.Submission(result.getId(),revision,manifestSha256);}
    }
    public record Lookup(String canonicalManifest,String manifestSha256,String status,long leaseExpiresAt) { }
    private static final String DEFINITION="""
        SELECT id,execution_revision,execution_definition_sha256,execution_definition_json::text definition,
            execution_approval::text approval,schedule_version,cron_binding_generation,cron_actor::text actor,
            cron_approved_execution_revision,status,cron_expression
        FROM quality.quality_rule WHERE id=?
        """;
    public Definition definition(long id){return definition(id,false);}
    private Definition definition(long id,boolean lock) {
        var rows=jdbc.query(DEFINITION+(lock?" FOR UPDATE":""),(r,index)->new Definition(r.getLong("id"),r.getLong("execution_revision"),
                r.getString("execution_definition_sha256"),r.getString("definition"),r.getString("approval"),r.getLong("schedule_version"),
                r.getLong("cron_binding_generation"),r.getString("actor"),(Long)r.getObject("cron_approved_execution_revision"),r.getString("status"),r.getString("cron_expression")),id);
        if(rows.isEmpty())throw new IllegalArgumentException("接入任务不存在");return rows.getFirst();
    }
    /** Validate only the actual DB projection, never an HTTP definition copy. */
    public JsonNode executableDefinition(Definition definition) {
        JsonNode n=FixedJson.parse(definition.canonicalDefinition());
        FixedJson.fields(n,"schemaVersion","datasourceId","targetTable","ruleType","expression","threshold");
        if(FixedJson.number(n,"schemaVersion",1)!=1)throw FixedJson.invalid();
        FixedJson.number(n,"datasourceId",1);threshold(n);
        QualitySqlGeneratorV1.generate(FixedJson.text(n,"targetTable"),FixedJson.text(n,"ruleType"),FixedJson.text(n,"expression"));
        return n;
    }
    private static long threshold(JsonNode definition){if(definition.get("threshold").isNull())return 0;long value=FixedJson.number(definition,"threshold",0);if(value>Integer.MAX_VALUE)throw FixedJson.invalid();return value;}
    public Definition approve(Definition expected,QualityActor actor) {
        executableDefinition(expected);
        return transactions.execute(tx->{var current=definition(expected.id(),true);sameRevision(current,expected);
            int updated=jdbc.update("""
                UPDATE quality.quality_rule SET execution_approval=jsonb_build_object('uid',?::bigint,'username',?::text,
                    'accessVersion',?::bigint,'revision',execution_revision,'definitionSha256',execution_definition_sha256),
                    execution_approved_at=clock_timestamp(),cron_actor=NULL,cron_approved_execution_revision=NULL
                WHERE id=? AND execution_revision=? AND execution_definition_sha256=?
                """,actor.uid(),actor.username(),actor.accessVersion(),current.id(),expected.revision(),expected.definitionSha256());
            if(updated!=1)throw conflict();return definition(current.id());});
    }
    public Definition bindCron(long id,long expectedRevision,long expectedGeneration,QualityActor actor) {
        return transactions.execute(tx->{var current=definition(id,true);requireApproved(current);
            if(current.revision()!=expectedRevision || current.scheduleGeneration()!=expectedGeneration || !"enabled".equals(current.status()) || current.cron()==null || current.cron().isBlank())throw conflict();
            int updated=jdbc.update("""
                UPDATE quality.quality_rule SET cron_actor=?::jsonb,cron_approved_execution_revision=execution_revision
                WHERE id=? AND execution_revision=? AND schedule_version=? AND execution_approval IS NOT NULL
                """,actor.forCron().json().toString(),id,expectedRevision,expectedGeneration);
            if(updated!=1)throw conflict();return definition(id);});
    }
    public Claim claimManual(long ruleId,QualityActor actor,String key) {
        if(actor==null||!"manual".equals(actor.json().path("kind").asText()))throw new IllegalStateException("手动执行需要可信人工主体");
        try{return transactions.execute(tx->claim(ruleId,actor,key,null,null));}
        catch(org.springframework.dao.DataIntegrityViolationException concurrent){throw conflict();}
    }
    public Claim claimScheduled(long ruleId,long generation,String key) {
        try{return transactions.execute(tx->claim(ruleId,null,key,generation,null));}
        catch(org.springframework.dao.DataIntegrityViolationException concurrent){throw conflict();}
    }
    public Claim claimChild(com.aetheris.security.protocol.execution.WorkflowQualityParent parent){
        Objects.requireNonNull(parent);return transactions.execute(tx->claim(parent.qualityRuleId(),QualityActor.child(parent),null,null,parent));
    }
    private Claim claim(long ruleId,QualityActor actor,String key,Long generation,com.aetheris.security.protocol.execution.WorkflowQualityParent parent) {
        if(key!=null && (key.isBlank()||key.length()>128))throw new IllegalArgumentException("幂等键格式无效");
        Definition current=definition(ruleId,true);requireApproved(current);JsonNode execution=executableDefinition(current);long threshold=threshold(execution);String target=FixedJson.text(execution,"targetTable");
        if(!"enabled".equals(current.status()))throw conflict();
        if(parent!=null){
            if(current.revision()!=parent.qualityExecutionRevision()||!current.definitionSha256().equals(parent.qualityDefinitionSha256()))throw conflict();
            var ref=parent.reference();var old=jdbc.queryForList("SELECT id,manifest_sha256 FROM quality.quality_check_result WHERE parent_origin='workflow' AND parent_run_id=? AND parent_attempt_id=? AND parent_fence=? AND parent_manifest_sha256=? AND rule_id=?",ref.instanceId(),ref.attemptId(),ref.fence(),ref.manifestSha256(),ruleId);
            if(!old.isEmpty()){var row=old.getFirst();return new Claim(Objects.requireNonNull(results.getById(((Number)row.get("id")).longValue())),current.revision(),(String)row.get("manifest_sha256"),threshold,target,true);}
        }
        if(generation!=null) {
            if(current.scheduleGeneration()!=generation || !current.cronBound())throw conflict();
            actor=QualityActor.cron(FixedJson.parse(current.cronActor()));
        }
        if(key!=null) {
            var existing=jdbc.queryForList("SELECT id,status,execution_revision,manifest_sha256 FROM quality.quality_check_result WHERE rule_id=? AND request_key=?",ruleId,key);
            if(!existing.isEmpty()) {
                var old=existing.getFirst();if(!Set.of("passed","failed","error").contains(old.get("status")))throw conflict();
                if(old.get("execution_revision")==null || ((Number)old.get("execution_revision")).longValue()!=current.revision())throw conflict();
                return new Claim(Objects.requireNonNull(results.getById(((Number)old.get("id")).longValue())),current.revision(),(String)old.get("manifest_sha256"),threshold,target,true);
            }
        }
        String actorJson=actor.json().toString();String parentJson=parent==null?"null":parent.parent().toString();var ref=parent==null?null:parent.reference();
        Long id=jdbc.queryForObject("""
            INSERT INTO quality.quality_check_result(rule_id,status,request_key,triggered_by,execution_snapshot,threshold_value)
            VALUES(?,'running',?,?,?,?) RETURNING id
            """,Long.class,ruleId,key,actor.username(),current.canonicalDefinition(),threshold);
        recovery.claim(id);
        var sealed=jdbc.queryForMap("""
            WITH manifest AS (
                SELECT jsonb_build_object('schemaVersion',1,'origin','quality','operation','metadata.fixed-quality-query.submit',
                    'resourceId',id,'runId',?::bigint,'attemptId',0,'fence',1,'executionRevision',execution_revision,
                    'definitionSha256',execution_definition_sha256,'actor',?::jsonb,'approval',execution_approval,
                    'execution',execution_definition_json,'parameters','{}'::jsonb,'parent',?::jsonb) body,
                    schedule_version,cron_binding_generation
                FROM quality.quality_rule WHERE id=?
            ) UPDATE quality.quality_check_result run SET execution_revision=?,invocation_json=?::jsonb,approval_json=?::jsonb,
                sealed_manifest=manifest.body,manifest_sha256=encode(sha256(convert_to(manifest.body::text,'UTF8')),'hex'),
                manifest_sealed_at=clock_timestamp(),parent_origin=?,parent_run_id=?,parent_attempt_id=?,parent_fence=?,parent_manifest_sha256=?,
                source_schedule_generation=CASE WHEN manifest.body->'actor'->>'kind'='cron' THEN manifest.schedule_version ELSE NULL END,
                source_binding_generation=CASE WHEN manifest.body->'actor'->>'kind'='cron' THEN manifest.cron_binding_generation ELSE NULL END
            FROM manifest WHERE run.id=? AND run.status='running' AND run.lease_owner IS NOT NULL AND run.lease_expires_at>clock_timestamp()
            RETURNING run.manifest_sha256
            """,id,actorJson,parentJson,ruleId,current.revision(),actorJson,current.approval(),parent==null?null:"workflow",ref==null?null:ref.instanceId(),ref==null?null:ref.attemptId(),ref==null?null:ref.fence(),ref==null?null:ref.manifestSha256(),id);
        return new Claim(Objects.requireNonNull(results.getById(id)),current.revision(),(String)sealed.get("manifest_sha256"),threshold,target,false);
    }
    public void bindReceipt(long runId,long receiptId) {
        if(receiptId<=0 || jdbc.update("""
            UPDATE quality.quality_check_result SET metadata_receipt_id=? WHERE id=? AND manifest_sealed_at IS NOT NULL AND status='running' AND lease_owner IS NOT NULL AND lease_expires_at>clock_timestamp()
              AND (metadata_receipt_id IS NULL OR metadata_receipt_id=?)
            """,receiptId,runId,receiptId)!=1)throw conflict();
    }
    public Lookup lookup(long runId,long fence) {
        if(runId<=0||fence!=1)throw FixedJson.invalid();
        var rows=jdbc.query("""
            SELECT run.sealed_manifest::text canonical,run.manifest_sha256,run.status,
                floor(extract(epoch FROM run.lease_expires_at))::bigint lease
            FROM quality.quality_check_result run JOIN quality.quality_rule task ON task.id=run.rule_id
            WHERE run.id=? AND run.execution_fence=? AND run.manifest_sealed_at IS NOT NULL
              AND run.status='running' AND run.lease_owner IS NOT NULL AND run.lease_expires_at>clock_timestamp()
              AND task.status='enabled' AND task.execution_approval IS NOT NULL
              AND task.execution_revision=run.execution_revision AND task.execution_approval=run.approval_json
              AND run.execution_snapshot::jsonb=task.execution_definition_json
              AND run.manifest_sha256=encode(sha256(convert_to(run.sealed_manifest::text,'UTF8')),'hex')
              AND run.sealed_manifest=jsonb_build_object('schemaVersion',1,'origin','quality','operation','metadata.fixed-quality-query.submit',
                  'resourceId',task.id,'runId',run.id,'attemptId',0,'fence',run.execution_fence,'executionRevision',run.execution_revision,
                  'definitionSha256',task.execution_definition_sha256,'actor',run.invocation_json,'approval',run.approval_json,
                  'execution',task.execution_definition_json,'parameters','{}'::jsonb,'parent',run.sealed_manifest->'parent')
              AND ((run.invocation_json->>'kind'='manual' AND run.source_schedule_generation IS NULL AND run.source_binding_generation IS NULL)
                  OR (run.invocation_json->>'kind'='cron' AND run.source_schedule_generation=task.schedule_version
                  AND run.source_binding_generation=task.cron_binding_generation
                  AND run.invocation_json=task.cron_actor AND task.cron_approved_execution_revision=run.execution_revision)
                OR (run.invocation_json->>'kind'='workflow-child' AND run.parent_origin='workflow'
                  AND run.sealed_manifest->'parent'->>'runId'=run.parent_run_id::text
                  AND run.sealed_manifest->'parent'->>'attemptId'=run.parent_attempt_id::text
                  AND run.sealed_manifest->'parent'->>'fence'=run.parent_fence::text
                  AND run.sealed_manifest->'parent'->>'manifestSha256'=run.parent_manifest_sha256
                  AND run.source_schedule_generation IS NULL AND run.source_binding_generation IS NULL))
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
