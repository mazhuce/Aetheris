package com.aetheris.metadata.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only invoked by the owned, disposable PostgreSQL audit; no application or business DB. */
class FixedExecutionStoragePostgresIT {
    static String url,password;
    static final String ACTOR="{\"kind\":\"manual\",\"uid\":2,\"username\":\"operator\",\"accessVersion\":0,\"requestId\":\"storage-audit\"}";
    static final String APPROVAL="{\"uid\":3,\"username\":\"approver\",\"accessVersion\":0,\"revision\":1,\"definitionSha256\":\""+"a".repeat(64)+"\"}";
    @BeforeAll static void install() throws Exception {
        url=System.getenv("AETHERIS_FIXED_STORAGE_URL");password=System.getenv("AETHERIS_FIXED_STORAGE_PASSWORD");
        assertNotNull(url,"Use scripts/audit-fixed-execution-storage.py");
        URI endpoint=URI.create(url.substring(5));assertEquals("127.0.0.1",endpoint.getHost());
        assertTrue(endpoint.getPort()>0);assertEquals("/aetheris_fixed_storage_audit",endpoint.getPath());
        assertNotNull(password);assertTrue(password.matches("[A-Za-z0-9_-]{32,}"));
        for(String service:List.of("metadata","workflow","ingestion","quality")) {
            Path path=Path.of("..").resolve(service).resolve("src/main/resources/db/migration").toAbsolutePath().normalize();
            var flyway=Flyway.configure().dataSource(url,"audit_admin",password).schemas(service)
                    .locations("filesystem:"+path).load();
            assertTrue(flyway.migrate().migrationsExecuted>0);assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals(0,flyway.info().pending().length);
        }
    }
    static Connection connection() throws SQLException {return DriverManager.getConnection(url,"audit_admin",password);}
    static long id(Connection db,String sql) throws SQLException {
        try(var statement=db.createStatement();var rows=statement.executeQuery(sql)){assertTrue(rows.next());return rows.getLong(1);}
    }
    static void execute(Connection db,String sql) throws SQLException {try(var statement=db.createStatement()){statement.execute(sql);}}
    static String value(Connection db,String sql) throws SQLException {try(var statement=db.createStatement();var rows=statement.executeQuery(sql)){assertTrue(rows.next());return rows.getString(1);}}
    static String literal(String value){return "'"+value.replace("'","''")+"'";}
    static String approvalSql(){return "jsonb_build_object('uid',3,'username','approver','accessVersion',0,'revision',execution_revision,'definitionSha256',execution_definition_sha256)";}

    @Test void definitionsInvalidateApprovalAndCronOnlyOnExecutionChanges() throws Exception {
        try(var db=connection()) {
            for(String schema:List.of("workflow","ingestion","quality")) {
                String table=schema.equals("workflow")?"task":schema.equals("ingestion")?"ingestion_task":"quality_rule";
                String full=schema+"."+table;
                String insert=switch(schema) {
                    case "workflow"->"INSERT INTO workflow.task(name,task_type,datasource_id,task_sql,status,cron_expression) VALUES('definition','sql',1,'INSERT INTO business.t VALUES (1)','enabled','0 0 1 * * ?') RETURNING id";
                    case "ingestion"->"INSERT INTO ingestion.ingestion_task(name,source_type,source_datasource_id,target_datasource_id,source_sql,target_table,status,schedule_cron) VALUES('definition','jdbc',1,2,'SELECT 1','business.t','enabled','0 0 1 * * ?') RETURNING id";
                    default->"INSERT INTO quality.quality_rule(name,rule_type,datasource_id,target_table,expression,threshold,status,cron_expression) VALUES('definition','sql',1,'business.t','SELECT 0',0,'enabled','0 0 1 * * ?') RETURNING id";
                };
                long resource=id(db,insert);
                try(var rows=db.createStatement().executeQuery("SELECT execution_revision,execution_approval,cron_actor,execution_definition_json,execution_definition_sha256 FROM "+full+" WHERE id="+resource)) {
                    assertTrue(rows.next());assertEquals(1,rows.getLong(1));assertNull(rows.getString(2));assertNull(rows.getString(3));assertNotNull(rows.getString(4));assertTrue(rows.getString(5).matches("[a-f0-9]{64}"));
                }
                execute(db,"UPDATE "+full+" SET execution_approval="+approvalSql()+",execution_approved_at=clock_timestamp(),cron_actor="+literal(ACTOR.replace("manual","cron"))+"::jsonb,cron_approved_execution_revision=execution_revision WHERE id="+resource);
                long generation=id(db,"SELECT cron_binding_generation FROM "+full+" WHERE id="+resource);
                execute(db,"UPDATE "+full+" SET name='display only',description='unrelated',updated_at=clock_timestamp() WHERE id="+resource);
                assertEquals(1,id(db,"SELECT execution_revision FROM "+full+" WHERE id="+resource));
                assertEquals(generation,id(db,"SELECT cron_binding_generation FROM "+full+" WHERE id="+resource));
                assertEquals(1,id(db,"SELECT count(*) FROM "+full+" WHERE id="+resource+" AND execution_approval IS NOT NULL AND cron_actor IS NOT NULL"));
                if(!schema.equals("workflow")) {
                    long schedule=id(db,"SELECT schedule_version FROM "+full+" WHERE id="+resource);
                    execute(db,"UPDATE "+full+" SET cron_actor="+literal(ACTOR.replace("manual","cron").replace("storage-audit","rebind-audit"))+"::jsonb WHERE id="+resource);
                    assertTrue(id(db,"SELECT schedule_version FROM "+full+" WHERE id="+resource)>schedule);
                    assertEquals(1,id(db,"SELECT count(*) FROM "+schema+".scheduler_sync_outbox o JOIN "+full+" t ON t.id=o.resource_id WHERE t.id="+resource+" AND o.generation=t.schedule_version AND o.enabled"));
                }
                String changed=switch(schema){case "workflow"->"task_sql='INSERT INTO business.t VALUES (2)'";case "ingestion"->"source_sql='SELECT 2'";default->"threshold=1";};
                execute(db,"UPDATE "+full+" SET "+changed+",execution_revision=999 WHERE id="+resource);
                assertEquals(2,id(db,"SELECT execution_revision FROM "+full+" WHERE id="+resource));
                assertEquals(1,id(db,"SELECT count(*) FROM "+full+" WHERE id="+resource+" AND execution_approval IS NULL AND cron_actor IS NULL AND execution_approved_at IS NULL"));
                assertTrue(id(db,"SELECT cron_binding_generation FROM "+full+" WHERE id="+resource)>generation);
                if(!schema.equals("workflow"))assertEquals(1,id(db,"SELECT count(*) FROM "+schema+".scheduler_sync_outbox o JOIN "+full+" t ON t.id=o.resource_id WHERE t.id="+resource+" AND o.generation=t.schedule_version AND NOT o.enabled"));
                assertEquals("23514",assertThrows(SQLException.class,()->execute(db,"UPDATE "+full+" SET execution_approval="+literal(APPROVAL)+"::jsonb,execution_approved_at=clock_timestamp() WHERE id="+resource)).getSQLState());
            }
        }
    }

    @Test void sealedRunsRejectMutationAndAllowLeaseAndReceiptUpdates() throws Exception {
        try(var db=connection()) {
            long task=id(db,"INSERT INTO ingestion.ingestion_task(name,source_type,source_datasource_id,target_datasource_id,source_sql,target_table,status) VALUES('sealed','jdbc',1,2,'SELECT ''中文'' AS label','business.t','enabled') RETURNING id");
            long rule=id(db,"INSERT INTO quality.quality_rule(name,rule_type,target_table,status) VALUES('sealed','sql','business.t','enabled') RETURNING id");
            execute(db,"UPDATE ingestion.ingestion_task SET execution_approval="+approvalSql()+",execution_approved_at=clock_timestamp() WHERE id="+task);
            execute(db,"UPDATE quality.quality_rule SET execution_approval="+approvalSql()+",execution_approved_at=clock_timestamp() WHERE id="+rule);
            for(var item:Map.of("ingestion.ingestion_run",Map.entry("task_id",task),"quality.quality_check_result",Map.entry("rule_id",rule)).entrySet()) {
                String table=item.getKey(),fk=item.getValue().getKey();long resource=item.getValue().getValue();
                long run=id(db,"INSERT INTO "+table+"("+fk+",status,triggered_by,started_at) VALUES("+resource+",'running','operator',clock_timestamp()) RETURNING id");
                String origin=table.startsWith("quality")?"quality":"ingestion";
                String definition=origin+"."+(origin.equals("quality")?"quality_rule":"ingestion_task");
                String approval=value(db,"SELECT execution_approval::text FROM "+definition+" WHERE id="+resource);
                String hash=value(db,"SELECT execution_definition_sha256 FROM "+definition+" WHERE id="+resource);
                String definitionJson=value(db,"SELECT execution_definition_json::text FROM "+definition+" WHERE id="+resource);
                execute(db,"UPDATE "+table+" SET execution_snapshot="+literal(definitionJson)+",lease_owner='worker',lease_expires_at=clock_timestamp()+interval '120 seconds' WHERE id="+run);
                String manifest="jsonb_build_object('schemaVersion',1,'origin','"+origin+"','operation','metadata.fixed-"+(origin.equals("quality")?"quality-query":"transfer")+".submit','resourceId',"+resource+",'runId',"+run+",'attemptId',0,'fence',1,'executionRevision',1,'definitionSha256',"+literal(hash)+",'actor',"+literal(ACTOR)+"::jsonb,'approval',"+literal(approval)+"::jsonb,'execution',"+literal(definitionJson)+"::jsonb,'parameters','{}'::jsonb,'parent',NULL)";
                String seal="execution_revision=1,invocation_json="+literal(ACTOR)+"::jsonb,approval_json="+literal(approval)+"::jsonb,sealed_manifest="+manifest+",manifest_sha256=encode(sha256(convert_to(("+manifest+")::text,'UTF8')),'hex'),manifest_sealed_at=clock_timestamp()";
                String wrongActor=seal.replace("'actor',"+literal(ACTOR),"'actor',"+literal(ACTOR.replace("operator","other")));
                assertThrows(SQLException.class,()->execute(db,"UPDATE "+table+" SET "+wrongActor+" WHERE id="+run),"Manifest actor must match its separately sealed actor");
                String changedExecution=seal.replace("'execution',"+literal(definitionJson),"'execution','{\"sql\":\"DELETE FROM business.t\"}'");
                assertThrows(SQLException.class,()->execute(db,"UPDATE "+table+" SET "+changedExecution+" WHERE id="+run),"Hash-consistent replacement execution cannot use an unrelated approval");
                execute(db,"UPDATE "+table+" SET "+seal+" WHERE id="+run);
                if(origin.equals("ingestion")) {
                    String canonical=value(db,"SELECT sealed_manifest::text FROM "+table+" WHERE id="+run);
                    String manifestHash=value(db,"SELECT manifest_sha256 FROM "+table+" WHERE id="+run);
                    var parsed=com.aetheris.metadata.fixedexecution.FixedTransferManifest.parse(canonical,manifestHash,
                            new com.aetheris.metadata.fixedexecution.FixedTransferManifest.Submit(run,0,1,1,manifestHash));
                    assertEquals(1,parsed.definition().sourceDatasourceId());assertEquals(2,parsed.definition().targetDatasourceId());
                    assertEquals("SELECT '中文' AS label",parsed.definition().sourceSql());
                    assertEquals(500,parsed.definition().batchSize());assertEquals("append",parsed.definition().writeMode());
                }
                for(String change:List.of("invocation_json='{}'::jsonb","approval_json='{}'::jsonb","execution_snapshot='changed'","sealed_manifest='[]'::jsonb","manifest_sealed_at=NULL","execution_fence=2")) {
                    assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"UPDATE "+table+" SET "+change+" WHERE id="+run)).getSQLState());
                }
                execute(db,"UPDATE "+table+" SET lease_owner='worker',lease_expires_at=clock_timestamp()+interval '120 seconds',metadata_receipt_id=99 WHERE id="+run);
                assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"UPDATE "+table+" SET metadata_receipt_id=100 WHERE id="+run)).getSQLState());
                execute(db,"UPDATE "+table+" SET status='unknown' WHERE id="+run);
                assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"DELETE FROM "+table+" WHERE id="+run)).getSQLState());
                assertEquals(1,id(db,"SELECT count(*) FROM "+table+" WHERE id="+run+" AND status='unknown' AND metadata_receipt_id=99"));
            }
        }
    }

    static String receiptInsert(String hash) {
        return "INSERT INTO metadata.execution_receipt(origin,operation,run_id,attempt_id,source_fence,execution_revision,manifest_sha256,raw_request_sha256,source_manifest_json,execution_json,deadline_at) VALUES('ingestion','metadata.fixed-transfer.submit',11,0,1,1,'"+hash+"','"+"b".repeat(64)+"','{}','{}',clock_timestamp()+interval '5 minutes')";
    }
    @Test void concurrentReceiptAdmissionIsUniqueAndUnknownCannotExecuteOrDisappear() throws Exception {
        String hash;
        try(var db=connection();var rows=db.createStatement().executeQuery("SELECT encode(sha256(convert_to('{}'::jsonb::text,'UTF8')),'hex')")){assertTrue(rows.next());hash=rows.getString(1);}
        var pool=Executors.newFixedThreadPool(8);var ready=new CountDownLatch(8);var start=new CountDownLatch(1);
        try {
            List<Future<Integer>> futures=new ArrayList<>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));try(var db=connection();var sql=db.createStatement()){return sql.executeUpdate(receiptInsert(hash)+" ON CONFLICT DO NOTHING");}}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();int inserted=0;
            for(var future:futures)inserted+=future.get(20,TimeUnit.SECONDS);assertEquals(1,inserted);
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        try(var db=connection()) {
            long receipt=id(db,"SELECT id FROM metadata.execution_receipt WHERE origin='ingestion' AND run_id=11");
            assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"UPDATE metadata.execution_receipt SET status='succeeded' WHERE id="+receipt)).getSQLState());
            assertEquals("23505",assertThrows(SQLException.class,()->execute(db,receiptInsert(hash))).getSQLState());
            assertEquals("23502",assertThrows(SQLException.class,()->execute(db,receiptInsert(hash).replace("11,0,1,1","12,NULL,1,1"))).getSQLState());
            execute(db,"UPDATE metadata.execution_receipt SET status='executing',worker_id='one',worker_fence=1,worker_lease_expires_at=clock_timestamp()+interval '120 seconds',started_at=clock_timestamp() WHERE id="+receipt);
            execute(db,"UPDATE metadata.execution_receipt SET status='unknown',safe_error='RESTART_OUTCOME_UNKNOWN' WHERE id="+receipt);
            for(String change:List.of("status='queued'","status='executing'","execution_json='{\"sql\":\"changed\"}'::jsonb","source_fence=2","worker_fence=2","status='succeeded'"))
                assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"UPDATE metadata.execution_receipt SET "+change+" WHERE id="+receipt)).getSQLState());
            assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"DELETE FROM metadata.execution_receipt WHERE id="+receipt)).getSQLState());
            execute(db,"UPDATE metadata.execution_receipt SET status='succeeded',finished_at=clock_timestamp(),safe_error=NULL,reconciliation_evidence='Isolated audit confirmed remote result',result_json='{\"rowsWritten\":1}' WHERE id="+receipt);
            assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"UPDATE metadata.execution_receipt SET status='executing' WHERE id="+receipt)).getSQLState());
            assertEquals(1,id(db,"SELECT count(*) FROM metadata.execution_receipt WHERE id="+receipt+" AND status='succeeded'"));
        }
    }

    @Test void workflowSealingRequiresCurrentAttemptAndPreservesItsParameters() throws Exception {
        try(var db=connection()) {
            long task=id(db,"INSERT INTO workflow.task(name,task_type,datasource_id,task_sql,status) VALUES('sealed-workflow','sql',1,'INSERT INTO business.t VALUES (1)','enabled') RETURNING id");
            execute(db,"UPDATE workflow.task SET execution_approval="+approvalSql()+",execution_approved_at=clock_timestamp() WHERE id="+task);
            long instance=id(db,"INSERT INTO workflow.task_instance(task_id,biz_date,status,fencing_token,lease_expires_at) VALUES("+task+",'2026-10-18','running',1,clock_timestamp()+interval '120 seconds') RETURNING id");
            long attempt=id(db,"INSERT INTO workflow.execution_attempt(instance_id,attempt_no,status,fencing_token,worker_id,trigger_type,triggered_by,task_name,task_sql,datasource_id,definition_digest,parameter_digest,lease_expires_at) VALUES("+instance+",1,'running',1,'worker','manual','operator','sealed-workflow','INSERT INTO business.t VALUES (1)',1,'"+"a".repeat(64)+"','"+"b".repeat(64)+"',clock_timestamp()+interval '120 seconds') RETURNING id");
            String approval=value(db,"SELECT execution_approval::text FROM workflow.task WHERE id="+task);
            String hash=value(db,"SELECT execution_definition_sha256 FROM workflow.task WHERE id="+task);
            String definitionJson=value(db,"SELECT execution_definition_json::text FROM workflow.task WHERE id="+task);
            execute(db,"UPDATE workflow.execution_attempt SET parameters_json='{\"bizDate\":\"2026-10-18\"}' WHERE id="+attempt);
            String manifest="jsonb_build_object('schemaVersion',1,'origin','workflow','operation','metadata.fixed-sql.submit','resourceId',"+task+",'runId',"+instance+",'attemptId',"+attempt+",'fence',1,'executionRevision',1,'definitionSha256',"+literal(hash)+",'actor',"+literal(ACTOR)+"::jsonb,'approval',"+literal(approval)+"::jsonb,'execution',"+literal(definitionJson)+"::jsonb,'parameters','{\"bizDate\":\"2026-10-18\"}'::jsonb,'parent',NULL)";
            String seal="execution_revision=1,invocation_json="+literal(ACTOR)+"::jsonb,approval_json="+literal(approval)+"::jsonb,sealed_manifest="+manifest+",manifest_sha256=encode(sha256(convert_to(("+manifest+")::text,'UTF8')),'hex'),manifest_sealed_at=clock_timestamp()";
            assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"UPDATE workflow.execution_attempt SET "+seal+" WHERE id="+attempt)).getSQLState(),"Unlinked attempt cannot be sealed");
            execute(db,"UPDATE workflow.task_instance SET active_attempt_id="+attempt+" WHERE id="+instance);
            execute(db,"UPDATE workflow.execution_attempt SET "+seal+" WHERE id="+attempt);
            for(String change:List.of("parameters_json='{\"bizDate\":\"2020-01-01\"}'::jsonb","task_sql='SELECT 123'","fencing_token=2","invocation_json=NULL"))
                assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"UPDATE workflow.execution_attempt SET "+change+" WHERE id="+attempt)).getSQLState());
            execute(db,"UPDATE workflow.execution_attempt SET heartbeat_at=clock_timestamp(),lease_expires_at=clock_timestamp()+interval '120 seconds',remote_handle='observed-handle',metadata_receipt_id=100 WHERE id="+attempt);
            assertEquals("55000",assertThrows(SQLException.class,()->execute(db,"DELETE FROM workflow.execution_attempt WHERE id="+attempt)).getSQLState());
        }
    }
}
