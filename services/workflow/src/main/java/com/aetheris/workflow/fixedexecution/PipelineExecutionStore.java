package com.aetheris.workflow.fixedexecution;

import com.aetheris.workflow.entity.TaskInstance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** One immutable plan, original UID/AV, and one fenced physical attempt per dispatched node. */
@Service
public class PipelineExecutionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WorkflowExecutionStore sql;
    private final String workerId="pipeline-"+UUID.randomUUID();
    public record Dispatch(long runId,long fence,String bizDate,String username,List<Long> order) {
        public Dispatch {order=List.copyOf(order);}
    }
    public PipelineExecutionStore(JdbcTemplate jdbc,PlatformTransactionManager manager,WorkflowExecutionStore sql) {
        this.jdbc=Objects.requireNonNull(jdbc);this.transactions=new TransactionTemplate(manager);this.sql=Objects.requireNonNull(sql);
    }
    private JsonNode plan(String value) {return FixedJson.parse(value.getBytes(StandardCharsets.UTF_8),16*1024*1024);}
    private Map<String,Object> lockedRun(long id) {
        var rows=jdbc.queryForList("SELECT *,invocation_json::text actor,plan_json::text plan FROM workflow.pipeline_run WHERE id=? FOR UPDATE",id);
        if(rows.size()!=1)throw new IllegalArgumentException("流水线运行不存在");return rows.getFirst();
    }
    private void requireSealed(Map<String,Object> run) {
        if(!(run.get("actor") instanceof String actor)||!(run.get("plan") instanceof String frozen))throw WorkflowExecutionStore.conflict();
        WorkflowActor.pipeline(FixedJson.parse(actor));
        var body=plan(frozen);FixedJson.fields(body,"schemaVersion","targetTaskId","bizDate","actor","nodes","executionOrder");
        if(FixedJson.number(body,"schemaVersion",1)!=1||!body.get("actor").equals(FixedJson.parse(actor)))throw FixedJson.invalid();
    }
    private Map<Long,WorkflowExecutionStore.Definition> lockDefinitions(List<Long> order) {
        var definitions=new LinkedHashMap<Long,WorkflowExecutionStore.Definition>();
        for(long id:order.stream().sorted().toList())definitions.put(id,sql.lockDefinition(id));
        return definitions;
    }
    private List<Long> edges(JsonNode execution) {
        var dependencies=execution.get("dependencies");
        if(dependencies==null||!dependencies.isObject())throw FixedJson.invalid();
        if(dependencies.isEmpty())return List.of();
        FixedJson.fields(dependencies,"upstreamTaskIds");var ids=dependencies.get("upstreamTaskIds");
        if(!ids.isArray())throw FixedJson.invalid();var result=new ArrayList<Long>();long last=0;
        for(var id:ids){if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<=last)throw FixedJson.invalid();last=id.longValue();result.add(last);}
        return result;
    }
    private List<Long> ids(JsonNode values) {
        if(values==null||!values.isArray())throw FixedJson.invalid();var result=new ArrayList<Long>();
        for(var id:values){if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<=0)throw FixedJson.invalid();result.add(id.longValue());}
        return result;
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private JsonNode definitionNode(WorkflowExecutionStore.Definition d) {
        // Typed approval admission for other engines is a separate prerequisite; never use the old string bridge.
        var execution=sql.executableDefinition(d);
        if(!"enabled".equals(d.status())||!d.approved())throw WorkflowExecutionStore.conflict();
        var approval=FixedJson.parse(d.approval());
        FixedJson.fields(approval,"uid","username","accessVersion","revision","definitionSha256");
        if(FixedJson.number(approval,"revision",1)!=d.revision()||!FixedJson.hash(approval,"definitionSha256").equals(d.definitionSha256()))throw WorkflowExecutionStore.conflict();
        return FixedJson.JSON.createObjectNode().put("taskId",d.id()).put("revision",d.revision()).put("definitionSha256",d.definitionSha256())
                .set("execution",execution);
    }
    private ObjectNode snapshot(WorkflowExecutionStore.Definition d) {
        var result=(ObjectNode)definitionNode(d);result.set("approval",FixedJson.parse(d.approval()));result.set("externalDependencies",FixedJson.JSON.createArrayNode());return result;
    }
    private void verifyClosure(long target,List<Long> order,Map<Long,WorkflowExecutionStore.Definition> definitions) {
        var seen=new HashSet<Long>();
        for(long id:order){for(long upstream:edges(sql.executableDefinition(definitions.get(id))))if(!seen.contains(upstream))throw WorkflowExecutionStore.conflict();seen.add(id);}
        var reachable=new HashSet<Long>();var pending=new ArrayDeque<Long>();pending.add(target);
        while(!pending.isEmpty()){long id=pending.removeFirst();if(reachable.add(id)){var d=definitions.get(id);if(d==null)throw WorkflowExecutionStore.conflict();pending.addAll(edges(sql.executableDefinition(d)));}}
        if(!reachable.equals(seen))throw WorkflowExecutionStore.conflict();
    }
    private ObjectNode externalEvidence(long sourceRun,long task) {
        var rows=jdbc.queryForList("""
            WITH RECURSIVE ancestry AS (
              SELECT id,retry_of_run_id FROM workflow.pipeline_run WHERE id=?
              UNION SELECT parent.id,parent.retry_of_run_id FROM workflow.pipeline_run parent JOIN ancestry child ON parent.id=child.retry_of_run_id)
            SELECT node.id,node.pipeline_run_id,node.instance_id,node.attempt_id,node.fencing_token
            FROM ancestry JOIN workflow.pipeline_node_run node ON node.pipeline_run_id=ancestry.id
            WHERE node.task_id=? AND workflow.pipeline_success_evidence(node.id) ORDER BY ancestry.id DESC LIMIT 1
            """,sourceRun,task);
        if(rows.size()!=1)throw new IllegalStateException("续跑依赖没有原流水线的有效成功凭证");var row=rows.getFirst();
        return FixedJson.JSON.createObjectNode().put("taskId",task).put("sourceRunId",number(row,"pipeline_run_id"))
                .put("nodeId",number(row,"id")).put("instanceId",number(row,"instance_id"))
                .put("attemptId",number(row,"attempt_id")).put("fence",number(row,"fencing_token"));
    }
    public long create(long target,String date,WorkflowActor actor,List<Long> fullOrder,List<Long> executionOrder,Long retryOf,Long resumeFrom,long leaseSeconds) {
        Objects.requireNonNull(actor);com.aetheris.workflow.config.BusinessCalendar.parseExact(date);
        fullOrder=List.copyOf(fullOrder);executionOrder=List.copyOf(executionOrder);
        if(fullOrder.isEmpty()||fullOrder.size()>512||new HashSet<>(fullOrder).size()!=fullOrder.size()||fullOrder.getLast()!=target
                ||executionOrder.isEmpty()||new HashSet<>(executionOrder).size()!=executionOrder.size()||!fullOrder.containsAll(executionOrder)
                ||!fullOrder.stream().filter(executionOrder::contains).toList().equals(executionOrder))throw FixedJson.invalid();
        final var frozenOrder=fullOrder;final var dispatchOrder=executionOrder;
        return Objects.requireNonNull(transactions.execute(tx->{
            Map<String,Object> source=retryOf==null?null:lockedRun(retryOf);
            if(source!=null){requireSealed(source);if(!Set.of("failed","cancelled").contains(source.get("status")))throw WorkflowExecutionStore.conflict();}
            jdbc.queryForList("SELECT pg_advisory_xact_lock(87316,hashtext(?))",target+"|"+date);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow.pipeline_run WHERE target_task_id=? AND biz_date=? AND status IN ('queued','running','unknown'))",Boolean.class,target,date)))throw new IllegalStateException("该目标在此业务日期有活动或待核对流水线");
            var definitions=lockDefinitions(frozenOrder);verifyClosure(target,frozenOrder,definitions);
            var nodes=FixedJson.JSON.createArrayNode();var executing=new HashSet<>(dispatchOrder);
            for(long id:frozenOrder){
                var node=snapshot(definitions.get(id));
                if(source!=null){
                    var prior=plan((String)source.get("plan"));var matching=new ArrayList<JsonNode>();
                    prior.get("nodes").forEach(n->{if(FixedJson.number(n,"taskId",1)==id)matching.add(n);});
                    if(FixedJson.number(prior,"targetTaskId",1)!=target||!FixedJson.text(prior,"bizDate").equals(date)
                            ||prior.get("nodes").size()!=frozenOrder.size()||ids(prior.get("executionOrder")).isEmpty()
                            ||matching.size()!=1||!node.get("execution").equals(matching.getFirst().get("execution"))
                            ||!node.get("approval").equals(matching.getFirst().get("approval"))
                            ||FixedJson.number(matching.getFirst(),"revision",1)!=definitions.get(id).revision())throw WorkflowExecutionStore.conflict();
                }
                if(executing.contains(id))for(long upstream:edges(node.get("execution")))if(!executing.contains(upstream)){
                    if(retryOf==null)throw WorkflowExecutionStore.conflict();((ArrayNode)node.get("externalDependencies")).add(externalEvidence(retryOf,upstream));
                }
                nodes.add(node);
            }
            var body=FixedJson.JSON.createObjectNode().put("schemaVersion",1).put("targetTaskId",target).put("bizDate",date);
            body.set("actor",actor.json());body.set("nodes",nodes);body.set("executionOrder",FixedJson.JSON.valueToTree(dispatchOrder));
            byte[] bytes=FixedJson.bytes(body);if(bytes.length>16*1024*1024)throw new IllegalArgumentException("流水线冻结定义超过16MiB预算");
            Long run=jdbc.queryForObject("""
                INSERT INTO workflow.pipeline_run(target_task_id,biz_date,status,triggered_by,heartbeat_at,lease_expires_at,retry_of_run_id,resume_from_task_id)
                VALUES(?,?,'queued',?,clock_timestamp(),clock_timestamp()+(?*interval '1 second'),?,?) RETURNING id
                """,Long.class,target,date,actor.username(),leaseSeconds,retryOf,resumeFrom);
            int sequence=0;for(long id:dispatchOrder){JsonNode n=null;for(var candidate:nodes)if(FixedJson.number(candidate,"taskId",1)==id)n=candidate;if(n==null)throw WorkflowExecutionStore.conflict();
                jdbc.update("""
                    INSERT INTO workflow.pipeline_node_run(pipeline_run_id,task_id,sequence_no,execution_revision,definition_sha256,definition_json,approval_json,dependency_evidence)
                    VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                    """,run,id,++sequence,definitions.get(id).revision(),definitions.get(id).definitionSha256(),n.get("execution").toString(),n.get("approval").toString(),n.get("externalDependencies").toString());
            }
            if(jdbc.update("UPDATE workflow.pipeline_run SET invocation_json=?::jsonb,plan_json=?::jsonb,plan_sha256=encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex') WHERE id=? AND plan_json IS NULL",
                    actor.json().toString(),new String(bytes,StandardCharsets.UTF_8),new String(bytes,StandardCharsets.UTF_8),run)!=1)throw WorkflowExecutionStore.conflict();
            return run;
        }));
    }
    public Dispatch begin(long id) {
        return transactions.execute(tx->{var run=lockedRun(id);requireSealed(run);
            if(!"queued".equals(run.get("status")))return null;
            var result=jdbc.queryForList("""
                UPDATE workflow.pipeline_run SET status='running',execution_fence=execution_fence+1,worker_id=?,
                  started_at=COALESCE(started_at,clock_timestamp()),heartbeat_at=clock_timestamp(),updated_at=clock_timestamp()
                WHERE id=? AND status='queued' AND lease_expires_at>clock_timestamp() RETURNING execution_fence
                """,workerId,id);
            if(result.size()!=1)return null;
            var actor=WorkflowActor.pipeline(FixedJson.parse((String)run.get("actor")));
            var order=jdbc.queryForList("SELECT task_id FROM workflow.pipeline_node_run WHERE pipeline_run_id=? AND status='queued' ORDER BY sequence_no",Long.class,id);
            return new Dispatch(id,number(result.getFirst(),"execution_fence"),(String)run.get("biz_date"),actor.username(),order);
        });
    }
    private Map<String,Object> owned(Dispatch dispatch) {
        var run=lockedRun(dispatch.runId());requireSealed(run);
        if(!"running".equals(run.get("status"))||number(run,"execution_fence")!=dispatch.fence()||!workerId.equals(run.get("worker_id"))
                ||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_expires_at>clock_timestamp() FROM workflow.pipeline_run WHERE id=?",Boolean.class,dispatch.runId())))throw WorkflowExecutionStore.conflict();
        return run;
    }
    public WorkflowExecutionStore.Claim prepare(Dispatch dispatch,long task) {
        return transactions.execute(tx->{var run=owned(dispatch);
            var node=jdbc.queryForList("SELECT * FROM workflow.pipeline_node_run WHERE pipeline_run_id=? AND task_id=? FOR UPDATE",dispatch.runId(),task);
            if(node.size()!=1||!"queued".equals(node.getFirst().get("status"))||node.getFirst().get("attempt_id")!=null)throw WorkflowExecutionStore.conflict();
            var body=plan((String)run.get("plan"));var full=new ArrayList<Long>();body.get("nodes").forEach(n->full.add(FixedJson.number(n,"taskId",1)));
            var definitions=lockDefinitions(full);
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT workflow.pipeline_plan_current(plan_json) FROM workflow.pipeline_run WHERE id=?",Boolean.class,dispatch.runId()))
                    ||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT workflow.pipeline_dependencies_ready(?,?)",Boolean.class,dispatch.runId(),number(node.getFirst(),"id"))))throw WorkflowExecutionStore.conflict();
            var execution=sql.executableDefinition(definitions.get(task));long timeout=execution.get("timeoutSeconds").isNull()?1800:FixedJson.number(execution,"timeoutSeconds",1);
            long queue="spark".equals(execution.path("taskType").asText())?WorkflowEngineMaterial.SPARK_QUEUE_SECONDS:0;
            jdbc.update("UPDATE workflow.pipeline_run SET heartbeat_at=clock_timestamp(),lease_expires_at=clock_timestamp()+((?::bigint+120)*interval '1 second') WHERE id=? AND execution_fence=?",timeout+queue,dispatch.runId(),dispatch.fence());
            return sql.claimPipeline(task,(String)run.get("biz_date"),WorkflowActor.pipeline(FixedJson.parse((String)run.get("actor"))),
                    new WorkflowExecutionStore.PipelineBinding(dispatch.runId(),number(node.getFirst(),"id"),dispatch.fence()));
        });
    }
    public boolean record(Dispatch dispatch,long task,WorkflowExecutionStore.Claim claim,TaskInstance result,String status,Long waiver) {
        return Boolean.TRUE.equals(transactions.execute(tx->{owned(dispatch);var e=claim.execution();
            if(result==null||!Objects.equals(result.getId(),e.instance().getId())||!Objects.equals(result.getActiveAttemptId(),e.attemptId())
                    ||!Objects.equals(result.getFencingToken(),e.fencingToken()))throw WorkflowExecutionStore.conflict();
            return jdbc.update("""
                UPDATE workflow.pipeline_node_run node SET status=?,waiver_id=?,message=?,finished_at=clock_timestamp()
                FROM workflow.task_instance instance,workflow.execution_attempt attempt
                WHERE node.pipeline_run_id=? AND node.task_id=? AND node.status='running'
                  AND node.instance_id=? AND node.attempt_id=? AND node.fencing_token=?
                  AND instance.id=node.instance_id AND instance.active_attempt_id=node.attempt_id AND instance.fencing_token=node.fencing_token
                  AND attempt.id=node.attempt_id AND attempt.fencing_token=node.fencing_token
                  AND (?='unknown' OR (attempt.status=CASE instance.status WHEN 'success' THEN 'succeeded' ELSE instance.status END AND attempt.finished_at IS NOT NULL AND instance.finished_at IS NOT NULL
                    AND CASE ? WHEN 'succeeded' THEN attempt.status='succeeded' WHEN 'cancelled' THEN attempt.status='cancelled'
                      WHEN 'failed' THEN attempt.status IN ('failed','timed_out','skipped')
                      WHEN 'waived' THEN attempt.status='failed' AND attempt.task_type='quality' AND attempt.error_code='QUALITY_GATE_FAILED'
                        AND attempt.quality_result_id IS NOT NULL AND attempt.quality_evidence_json->>'state'='failed'
                        AND (attempt.quality_evidence_json->>'violationCount')::bigint>(attempt.quality_evidence_json->>'thresholdValue')::bigint
                        AND EXISTS(SELECT 1 FROM workflow.quality_waiver waiver WHERE waiver.id=? AND waiver.quality_task_id=node.task_id
                          AND waiver.biz_date=instance.biz_date AND waiver.expires_at>clock_timestamp())
                      ELSE false END))
                """,status,waiver,result.getErrorMessage()==null?null:result.getErrorMessage().substring(0,Math.min(512,result.getErrorMessage().length())),dispatch.runId(),task,e.instance().getId(),e.attemptId(),e.fencingToken(),status,status,waiver)==1;
        }));
    }
    public void finish(Dispatch dispatch,String status,Long task,String message) {
        transactions.executeWithoutResult(tx->{owned(dispatch);
            String safe=message==null?null:message.substring(0,Math.min(512,message.length()));
            if("succeeded".equals(status)&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow.pipeline_node_run WHERE pipeline_run_id=? AND NOT workflow.pipeline_success_evidence(id))",Boolean.class,dispatch.runId())))throw WorkflowExecutionStore.conflict();
            if(task!=null)jdbc.update("UPDATE workflow.pipeline_node_run SET status=?,message=?,finished_at=COALESCE(finished_at,clock_timestamp()) WHERE pipeline_run_id=? AND task_id=? AND status IN ('queued','running')",
                    "unknown".equals(status)?"unknown":"cancelled".equals(status)?"cancelled":"failed",safe,dispatch.runId(),task);
            jdbc.update("UPDATE workflow.pipeline_node_run SET status='skipped',finished_at=clock_timestamp(),message=? WHERE pipeline_run_id=? AND status='queued'",
                    "unknown".equals(status)?"上游结果待核对，节点未执行":"上游节点未成功，节点未执行",dispatch.runId());
            if(jdbc.update("UPDATE workflow.pipeline_run SET status=?,error_message=?,finished_at=clock_timestamp(),heartbeat_at=clock_timestamp(),lease_expires_at=NULL,updated_at=clock_timestamp() WHERE id=? AND execution_fence=? AND status='running'",
                    status,safe,dispatch.runId(),dispatch.fence())!=1)throw WorkflowExecutionStore.conflict();
        });
    }
    /** No username reconstruction, latest-instance fallback, or automatic second physical submission. */
    public List<Long> recover() {
        return transactions.execute(tx->{var ready=new ArrayList<Long>();
            var candidates=jdbc.queryForList("""
                SELECT run.id FROM workflow.pipeline_run run
                WHERE run.status='unknown' AND run.plan_json IS NOT NULL AND (
                  EXISTS(SELECT 1 FROM workflow.pipeline_node_run node JOIN workflow.execution_attempt attempt ON attempt.id=node.attempt_id
                    WHERE node.pipeline_run_id=run.id AND node.status='unknown' AND attempt.status IN ('succeeded','failed','timed_out','cancelled'))
                  OR NOT EXISTS(SELECT 1 FROM workflow.pipeline_node_run node WHERE node.pipeline_run_id=run.id AND node.status NOT IN ('succeeded','waived','skipped','queued')))
                ORDER BY run.updated_at,run.id LIMIT 100
                """,Long.class);
            for(long id:candidates){
                var run=lockedRun(id);if(!"unknown".equals(run.get("status")))continue;requireSealed(run);
                // Fair bounded reconciliation: one permanently unresolved old run cannot starve newer evidence.
                jdbc.update("UPDATE workflow.pipeline_run SET updated_at=clock_timestamp() WHERE id=? AND status='unknown'",id);
                var nodes=jdbc.queryForList("""
                    SELECT node.id,node.sequence_no,attempt.status FROM workflow.pipeline_node_run node
                    JOIN workflow.execution_attempt attempt ON attempt.id=node.attempt_id AND attempt.instance_id=node.instance_id
                    JOIN workflow.task_instance instance ON instance.id=node.instance_id
                    WHERE node.pipeline_run_id=? AND node.status='unknown' AND node.fencing_token=attempt.fencing_token
                      AND instance.active_attempt_id=attempt.id AND instance.fencing_token=attempt.fencing_token
                      AND attempt.status=CASE instance.status WHEN 'success' THEN 'succeeded' ELSE instance.status END AND attempt.status IN ('succeeded','failed','timed_out','cancelled')
                      AND attempt.finished_at IS NOT NULL AND instance.finished_at IS NOT NULL AND attempt.manifest_sealed_at IS NOT NULL
                      AND attempt.pipeline_run_id=? AND attempt.pipeline_node_id=node.id AND attempt.pipeline_fence=?
                      AND attempt.invocation_json=?::jsonb AND attempt.execution_revision=node.execution_revision AND attempt.approval_json=node.approval_json
                      AND attempt.metadata_receipt_id IS NOT NULL FOR UPDATE OF node
                    """,id,id,number(run,"execution_fence"),(String)run.get("actor"));
                if(nodes.size()>1)continue;
                if(nodes.size()==1&&!"succeeded".equals(nodes.getFirst().get("status"))){
                    var node=nodes.getFirst();String state="cancelled".equals(node.get("status"))?"cancelled":"failed";
                    jdbc.update("UPDATE workflow.pipeline_node_run SET status=?,message='原封存attempt已核对为终态',finished_at=COALESCE(finished_at,clock_timestamp()) WHERE id=? AND status='unknown'",state,number(node,"id"));
                    jdbc.update("UPDATE workflow.pipeline_run SET status=?,error_message='原封存attempt已核对为终态',finished_at=clock_timestamp(),lease_expires_at=NULL,updated_at=clock_timestamp() WHERE id=? AND status='unknown'",state,id);
                    continue;
                }
                if(nodes.size()==1)jdbc.update("UPDATE workflow.pipeline_node_run SET status='succeeded',message='原封存attempt已核对为成功' WHERE id=? AND status='unknown'",number(nodes.getFirst(),"id"));
                if(Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM workflow.pipeline_node_run node WHERE node.pipeline_run_id=? AND
                      ((node.status IN ('succeeded','waived') AND NOT workflow.pipeline_success_evidence(node.id))
                        OR (node.status NOT IN ('succeeded','waived') AND NOT(node.status IN ('queued','skipped') AND node.attempt_id IS NULL))))
                    """,Boolean.class,id)))continue;
                boolean pending=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workflow.pipeline_node_run WHERE pipeline_run_id=? AND status NOT IN ('succeeded','waived'))",Boolean.class,id));
                if(!pending){
                    jdbc.update("UPDATE workflow.pipeline_run SET status='succeeded',error_message=NULL,finished_at=clock_timestamp(),lease_expires_at=NULL,updated_at=clock_timestamp() WHERE id=? AND status='unknown'",id);
                }else{
                    var body=plan((String)run.get("plan"));var full=new ArrayList<Long>();body.get("nodes").forEach(n->full.add(FixedJson.number(n,"taskId",1)));lockDefinitions(full);
                    if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT workflow.pipeline_plan_current(plan_json) FROM workflow.pipeline_run WHERE id=?",Boolean.class,id)))continue;
                    jdbc.update("UPDATE workflow.pipeline_node_run SET status='queued',started_at=NULL,finished_at=NULL,message=NULL WHERE pipeline_run_id=? AND status='skipped' AND attempt_id IS NULL",id);
                    jdbc.update("UPDATE workflow.pipeline_run SET status='queued',error_message=NULL,finished_at=NULL,heartbeat_at=clock_timestamp(),lease_expires_at=clock_timestamp()+interval '120 seconds',updated_at=clock_timestamp() WHERE id=? AND status='unknown'",id);
                    ready.add(id);
                }
            }
            return List.copyOf(ready);
        });
    }
    public void rejectQueued(long runId){
        transactions.executeWithoutResult(tx->{var run=lockedRun(runId);if(!"queued".equals(run.get("status")))return;
            jdbc.update("UPDATE workflow.pipeline_run SET status='failed',error_message='执行器未接受派发，未提交节点',finished_at=clock_timestamp(),lease_expires_at=NULL WHERE id=? AND status='queued'",runId);
            jdbc.update("UPDATE workflow.pipeline_node_run SET status='skipped',message='执行器未接受派发，节点未执行',finished_at=clock_timestamp() WHERE pipeline_run_id=? AND status='queued'",runId);
        });
    }
}
