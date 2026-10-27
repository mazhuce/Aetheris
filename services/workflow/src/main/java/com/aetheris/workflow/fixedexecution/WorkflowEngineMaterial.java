package com.aetheris.workflow.fixedexecution;

import com.aetheris.workflow.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Captures actual execution inputs under DB locks; no engine HTTP call is made in the transaction. */
@Component
public final class WorkflowEngineMaterial {
    public static final int SPARK_QUEUE_SECONDS=300;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ManagedSparkCatalogService catalog;
    private final OutboundTargetPolicy outbound;
    private final Environment environment;
    public WorkflowEngineMaterial(JdbcTemplate jdbc,PlatformTransactionManager manager,
            ManagedSparkCatalogService catalog,OutboundTargetPolicy outbound,Environment environment){
        this.jdbc=jdbc;this.transactions=new TransactionTemplate(manager);this.catalog=catalog;
        this.outbound=outbound;this.environment=environment;
    }
    public record Material(String canonical,String sha256){public JsonNode json(){return FixedJson.parse(canonical);}}
    public Material current(WorkflowExecutionStore.Definition definition){
        return transactions.execute(tx->{
            JsonNode task=FixedJson.parse(definition.canonicalDefinition());
            String engine=FixedJson.text(task,"taskType");
            ObjectNode value=switch(engine){case "spark"->spark(task);case "flink"->flink(task);default->throw FixedJson.invalid();};
            String canonical=jdbc.queryForObject("SELECT (?::jsonb)::text",String.class,value.toString());
            validate(FixedJson.parse(canonical),engine);
            return new Material(canonical,digest(canonical));
        });
    }
    public void requireCurrent(WorkflowExecutionStore.Definition d){
        JsonNode n=FixedJson.parse(d.canonicalDefinition());
        if(!n.hasNonNull("engineDefinition")||!n.get("engineDefinition").equals(current(d).json()))throw WorkflowExecutionStore.conflict();
    }
    private ObjectNode spark(JsonNode task){
        if(!environment.getProperty("aetheris.spark-runner.enabled",Boolean.class,false))throw new IllegalStateException("受管Spark运行器未启用");
        String token=environment.getProperty("aetheris.spark-runner.token","");
        if(token.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalStateException("受管Spark凭据尚未配置");
        long id=FixedJson.number(task,"jobDefinitionId",1);
        var links=jdbc.queryForList("SELECT artifact_id,environment_id FROM workflow.spark_job_definition WHERE id=? FOR SHARE",id);
        if(links.size()!=1)throw WorkflowExecutionStore.conflict();
        long artifact=((Number)links.getFirst().get("artifact_id")).longValue();
        long env=((Number)links.getFirst().get("environment_id")).longValue();
        var artifacts=jdbc.queryForList("SELECT repository_id FROM workflow.managed_artifact WHERE id=? FOR SHARE",artifact);
        if(artifacts.size()!=1)throw WorkflowExecutionStore.conflict();
        var environments=jdbc.queryForList("""
            SELECT jsonb_build_object('id',id,'key',environment_key,'adapter',adapter_type,'sparkVersion',spark_version,
              'javaVersion',java_version,'pythonVersion',python_version,'master',master,'maxCores',max_cores,
              'maxMemoryMb',max_memory_mb,'maxTimeoutSeconds',max_timeout_seconds,'maxConcurrency',max_concurrency)::text body
            FROM workflow.compute_environment WHERE id=? AND status='active' FOR SHARE
            """,env);
        if(environments.size()!=1)throw WorkflowExecutionStore.conflict();
        JsonNode repository=NullNode.instance;
        if(artifacts.getFirst().get("repository_id") instanceof Number repo){
            var rows=jdbc.queryForList("""
                SELECT jsonb_build_object('id',id,'url',remote_url,'signaturePolicy',signature_policy,
                  'trustedSigners',trusted_signer_fingerprints)::text body
                FROM workflow.source_repository WHERE id=? AND status='active' FOR SHARE
                """,repo.longValue());
            if(rows.size()!=1)throw WorkflowExecutionStore.conflict();repository=FixedJson.parse((String)rows.getFirst().get("body"));
        }
        var job=catalog.runnableJob(id);
        int parentTimeout=task.get("timeoutSeconds").isNull()?1800:Math.toIntExact(FixedJson.number(task,"timeoutSeconds",1));
        if(job.timeoutSeconds()>parentTimeout)throw new IllegalArgumentException("任务超时必须覆盖Spark作业的执行期限");
        ObjectNode n=FixedJson.JSON.createObjectNode().put("schemaVersion",1).put("engine","spark")
            .put("jobId",job.id()).put("jobVersion",job.version()).put("jobDefinitionSha256",job.definitionDigest())
            .put("environmentId",job.environmentId()).put("artifactId",job.artifactId()).put("artifactKey",job.artifactKey())
            .put("artifactType",job.artifactType()).put("artifactSha256",job.artifactDigest()).put("mainClass",job.mainClass())
            .put("outputTable",job.outputTable()).put("outputUri",job.outputUri()).put("writeMode",job.writeMode())
            .put("cores",job.driverCores()).put("memoryMb",job.driverMemoryMb()).put("timeoutSeconds",job.timeoutSeconds())
            .put("queueTimeoutSeconds",SPARK_QUEUE_SECONDS).put("runnerCredentialSha256",digest(token));
        n.set("parameterSchema",job.parameterSchema().deepCopy());n.set("parameters",job.defaultParameters().deepCopy());
        n.set("inputTables",FixedJson.JSON.valueToTree(job.inputTables()));n.set("environment",FixedJson.parse((String)environments.getFirst().get("body")));
        ObjectNode source=FixedJson.JSON.createObjectNode().put("repositoryId",job.sourceRepositoryId()).put("commitSha",job.sourceCommitSha())
            .put("path",job.sourcePath()).put("blobId",job.sourceBlobId()).put("blobSha256",job.sourceBlobSha256())
            .put("signatureStatus",job.sourceSignatureStatus()).put("signerFingerprint",job.sourceSignerFingerprint())
            .put("verifiedAt",job.sourceVerifiedAt()==null?null:job.sourceVerifiedAt().toString());
        source.set("repository",repository);n.set("source",source);
        ArrayNode runners=n.putArray("runners");
        var rows=jdbc.queryForList("""
            SELECT id,runner_key,base_url,max_cores,max_memory_mb,max_concurrency FROM workflow.compute_runner
            WHERE environment_id=? AND status='active' AND (?::bigint IS NULL OR id=?::bigint) ORDER BY id FOR SHARE
            """,env,job.runnerId(),job.runnerId());
        if(rows.isEmpty()||rows.size()>64)throw new IllegalStateException("Spark必须绑定1至64个明确启用的Runner");
        for(var row:rows)runners.addObject().put("id",((Number)row.get("id")).longValue()).put("key",(String)row.get("runner_key"))
            .put("origin",outbound.httpOrigin(SparkBatchExecutionAdapter.DEFAULT_RUNNER.equals(row.get("base_url"))
                ?environment.getProperty("aetheris.spark-runner.base-url","http://127.0.0.1:18081"):(String)row.get("base_url"))).put("maxCores",((Number)row.get("max_cores")).intValue())
            .put("maxMemoryMb",((Number)row.get("max_memory_mb")).intValue()).put("maxConcurrency",((Number)row.get("max_concurrency")).intValue());
        return n;
    }
    private ObjectNode flink(JsonNode task){
        String gateway=outbound.httpOrigin(FixedJson.text(task,"flinkEndpoint"));
        String manager=outbound.httpOrigin(environment.getProperty("aetheris.flink-jobmanager.base-url","http://127.0.0.1:18081"));
        // New approvals use the public/task CHECK contract; legacy material is never silently normalized.
        String mode=FixedJson.text(task,"flinkMode");if(!Set.of("bounded","streaming").contains(mode))throw FixedJson.invalid();
        FlinkSqlScript.statements(FixedJson.text(task,"sql"),mode.equals("streaming"));
        return FixedJson.JSON.createObjectNode().put("schemaVersion",1).put("engine","flink").put("gatewayOrigin",gateway)
            .put("jobManagerOrigin",manager).put("mode",mode).put("queueTimeoutSeconds",0);
    }
    public static void validate(JsonNode n,String engine){
        if(n==null||!n.isObject()||FixedJson.number(n,"schemaVersion",1)!=1||!engine.equals(FixedJson.text(n,"engine")))throw FixedJson.invalid();
        if(engine.equals("flink")){
            FixedJson.fields(n,"schemaVersion","engine","gatewayOrigin","jobManagerOrigin","mode","queueTimeoutSeconds");
            // "batch" remains readable in old sealed manifests/receipts. current() cannot emit it,
            // and requireCurrent() compares the exact material before granting a new dispatch.
            if(!Set.of("bounded","streaming","batch").contains(FixedJson.text(n,"mode"))||FixedJson.number(n,"queueTimeoutSeconds",0)!=0)throw FixedJson.invalid();
            FixedJson.text(n,"gatewayOrigin");FixedJson.text(n,"jobManagerOrigin");return;
        }
        FixedJson.fields(n,"schemaVersion","engine","jobId","jobVersion","jobDefinitionSha256","environmentId","artifactId","artifactKey","artifactType","artifactSha256","mainClass","outputTable","outputUri","writeMode","cores","memoryMb","timeoutSeconds","queueTimeoutSeconds","runnerCredentialSha256","parameterSchema","parameters","inputTables","environment","source","runners");
        FixedJson.number(n,"jobId",1);FixedJson.number(n,"jobVersion",1);FixedJson.number(n,"environmentId",1);FixedJson.number(n,"artifactId",1);
        FixedJson.hash(n,"jobDefinitionSha256");FixedJson.hash(n,"artifactSha256");FixedJson.hash(n,"runnerCredentialSha256");
        if(!Set.of("pyspark","spark-jar").contains(FixedJson.text(n,"artifactType"))||!"overwrite_partition".equals(FixedJson.text(n,"writeMode")))throw FixedJson.invalid();
        FixedJson.text(n,"artifactKey");FixedJson.text(n,"outputUri");
        if(FixedJson.number(n,"cores",1)>64||FixedJson.number(n,"memoryMb",512)>262144||FixedJson.number(n,"timeoutSeconds",1)>86400
                ||FixedJson.number(n,"queueTimeoutSeconds",0)!=SPARK_QUEUE_SECONDS||!n.get("parameters").isObject()
                ||!n.get("parameterSchema").isObject()||!n.get("inputTables").isArray()||!n.get("environment").isObject()||!n.get("source").isObject())throw FixedJson.invalid();
        if(!n.get("runners").isArray()||n.get("runners").isEmpty()||n.get("runners").size()>64)throw FixedJson.invalid();
        Set<Long> ids=new HashSet<>();for(var r:n.get("runners")){
            FixedJson.fields(r,"id","key","origin","maxCores","maxMemoryMb","maxConcurrency");
            if(!ids.add(FixedJson.number(r,"id",1)))throw FixedJson.invalid();FixedJson.text(r,"key");FixedJson.text(r,"origin");
            FixedJson.number(r,"maxCores",1);FixedJson.number(r,"maxMemoryMb",512);FixedJson.number(r,"maxConcurrency",1);
        }
    }
    public static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
