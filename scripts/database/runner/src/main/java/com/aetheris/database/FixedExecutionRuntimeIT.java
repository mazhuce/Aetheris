package com.aetheris.database;

import java.sql.*;
import java.util.*;
import static com.aetheris.database.DatabaseRunner.*;
import static com.aetheris.database.DatabaseRolesIT.*;

/** Writes are permitted only by explicit acceptance in its disposable loopback database. */
final class FixedExecutionRuntimeIT {
    private static final String ACTOR="{\"kind\":\"manual\",\"uid\":2,\"username\":\"roles_actor\",\"accessVersion\":0,\"requestId\":\"roles-fixed-audit\"}";
    static long number(Connection c,String sql) throws SQLException {return ((Number)scalar(c,sql)).longValue();}
    static void rejects(Connection c,String sql,String state,String label) throws Exception {
        Savepoint point=c.setSavepoint();
        try {execute(c,sql);throw new IllegalStateException("Forbidden fixed execution mutation succeeded: "+label);}
        catch(SQLException denied){require(state.equals(denied.getSQLState()),"Unexpected fixed execution SQL state: "+label);}
        finally{c.rollback(point);c.releaseSavepoint(point);}
        results.add(Map.of("case",label,"sqlState",state,"passed",true));
    }
    static void source(String schema) throws Exception {
        try(Connection c=service(schema)) {
            c.setAutoCommit(false);
            String table=schema.equals("workflow")?"task":schema.equals("ingestion")?"ingestion_task":"quality_rule";
            String full=q(schema)+"."+q(table);
            String insert=switch(schema){
                case "workflow"->"INSERT INTO workflow.task(name,task_type,datasource_id,task_sql,status) VALUES('roles_fixed','sql',1,'SELECT 1','enabled') RETURNING id";
                case "ingestion"->"INSERT INTO ingestion.ingestion_task(name,source_type,source_datasource_id,target_datasource_id,source_sql,target_table,status) VALUES('roles_fixed','jdbc',1,2,'SELECT 1','business.target','enabled') RETURNING id";
                default->"INSERT INTO quality.quality_rule(name,rule_type,datasource_id,target_table,expression,threshold,status) VALUES('roles_fixed','sql',1,'business.target','SELECT 0',0,'enabled') RETURNING id";
            };
            long resource=number(c,insert);
            execute(c,"UPDATE "+full+" SET execution_approval=jsonb_build_object('uid',3,'username','roles_approver','accessVersion',0,'revision',execution_revision,'definitionSha256',execution_definition_sha256),execution_approved_at=clock_timestamp() WHERE id="+resource);
            var definition=rows(c,"SELECT execution_definition_json::text AS definition,execution_definition_sha256 AS hash,execution_approval::text AS approval FROM "+full+" WHERE id="+resource).getFirst();
            String execution=literal(text(definition,"definition"))+"::jsonb",approval=literal(text(definition,"approval"))+"::jsonb";
            long logical,attempt;String runTable,heartbeat;
            if(schema.equals("workflow")) {
                logical=number(c,"INSERT INTO workflow.task_instance(task_id,biz_date,status,fencing_token,lease_expires_at) VALUES("+resource+",'2026-10-18','running',1,clock_timestamp()+interval '120 seconds') RETURNING id");
                attempt=number(c,"INSERT INTO workflow.execution_attempt(instance_id,attempt_no,status,fencing_token,worker_id,trigger_type,triggered_by,task_name,task_sql,datasource_id,definition_digest,parameter_digest,parameters_json,lease_expires_at) VALUES("+logical+",1,'running',1,'roles_worker','manual','roles_actor','roles_fixed','SELECT 1',1,"+literal(text(definition,"hash"))+",encode(sha256(convert_to(jsonb_build_object('bizDate','2026-10-18')::text,'UTF8')),'hex'),jsonb_build_object('bizDate','2026-10-18'),clock_timestamp()+interval '120 seconds') RETURNING id");
                execute(c,"UPDATE workflow.task_instance SET active_attempt_id="+attempt+" WHERE id="+logical);
                runTable="workflow.execution_attempt";heartbeat="heartbeat_at=clock_timestamp(),lease_expires_at=clock_timestamp()+interval '120 seconds'";
            }else {
                runTable=schema.equals("ingestion")?"ingestion.ingestion_run":"quality.quality_check_result";String fk=schema.equals("ingestion")?"task_id":"rule_id";
                logical=number(c,"INSERT INTO "+runTable+"("+fk+",status,triggered_by,lease_owner,execution_snapshot) VALUES("+resource+",'running','roles_actor','roles_worker',"+literal(text(definition,"definition"))+") RETURNING id");
                attempt=0;heartbeat="lease_expires_at=clock_timestamp()+interval '120 seconds'";
            }
            long row=schema.equals("workflow")?attempt:logical;
            String operation=schema.equals("workflow")?"metadata.fixed-sql.submit":schema.equals("ingestion")?"metadata.fixed-transfer.submit":"metadata.fixed-quality-query.submit";
            String parameters=schema.equals("workflow")?"jsonb_build_object('bizDate','2026-10-18')":"'{}'::jsonb";
            String manifest="jsonb_build_object('schemaVersion',1,'origin',"+literal(schema)+",'operation',"+literal(operation)+",'resourceId',"+resource+",'runId',"+logical+",'attemptId',"+attempt+",'fence',1,'executionRevision',1,'definitionSha256',"+literal(text(definition,"hash"))+",'actor',"+literal(ACTOR)+"::jsonb,'approval',"+approval+",'execution',"+execution+",'parameters',"+parameters+",'parent',NULL)";
            execute(c,"UPDATE "+runTable+" SET execution_revision=1,invocation_json="+literal(ACTOR)+"::jsonb,approval_json="+approval+",sealed_manifest="+manifest+",manifest_sha256=encode(sha256(convert_to(("+manifest+")::text,'UTF8')),'hex'),manifest_sealed_at=clock_timestamp() WHERE id="+row);
            execute(c,"UPDATE "+runTable+" SET "+heartbeat+",metadata_receipt_id=999 WHERE id="+row);
            require(number(c,"SELECT count(*) FROM "+runTable+" WHERE id="+row+" AND manifest_sealed_at IS NOT NULL AND metadata_receipt_id=999")==1,"Runtime seal/heartbeat not persisted");
            rejects(c,"UPDATE "+runTable+" SET invocation_json='{}'::jsonb WHERE id="+row,"55000",schema+":runtime-seal-immutable");
            rejects(c,"UPDATE "+runTable+" SET metadata_receipt_id=1000 WHERE id="+row,"55000",schema+":runtime-receipt-binding-immutable");
            require(Boolean.TRUE.equals(scalar(c,"SELECT "+q(schema)+".execution_identity_valid('{\"uid\":2,\"username\":\"roles_actor\",\"accessVersion\":0}'::jsonb)")),"Runtime helper execution missing");
            c.rollback();
            results.add(Map.of("case",schema+":runtime-definition-approve-seal-heartbeat","passed",true));
        }
    }
    static void receipt() throws Exception {
        try(Connection c=service("metadata")) {
            c.setAutoCommit(false);
            long id=number(c,"INSERT INTO metadata.execution_receipt(origin,operation,run_id,attempt_id,source_fence,execution_revision,manifest_sha256,raw_request_sha256,source_manifest_json,execution_json,deadline_at) VALUES('ingestion','metadata.fixed-transfer.submit',90001,0,1,1,encode(sha256(convert_to('{}'::jsonb::text,'UTF8')),'hex'),'"+"b".repeat(64)+"','{}','{}',clock_timestamp()+interval '5 minutes') RETURNING id");
            require(number(c,"UPDATE metadata.execution_receipt SET status='executing',worker_id='roles_worker',worker_fence=1,started_at=clock_timestamp(),worker_lease_expires_at=clock_timestamp()+interval '120 seconds' WHERE id="+id+" AND status='queued' RETURNING worker_fence")==1,"Runtime receipt claim CAS failed");
            execute(c,"UPDATE metadata.execution_receipt SET status='unknown',safe_error='ISOLATED_RESULT_UNKNOWN' WHERE id="+id);
            rejects(c,"UPDATE metadata.execution_receipt SET status='executing' WHERE id="+id,"55000","metadata:runtime-unknown-cannot-reexecute");
            rejects(c,"DELETE FROM metadata.execution_receipt WHERE id="+id,"55000","metadata:runtime-tombstone-retained");
            c.rollback();results.add(Map.of("case","metadata:runtime-receipt-claim-unknown","passed",true));
        }
    }
    static void privilegeNegatives() throws Exception {
        try(Connection c=admin()) {
            verifyRuntime(c);
            var mutations=List.of(
                List.of("missing-helper","REVOKE EXECUTE ON FUNCTION workflow.execution_identity_valid(jsonb) FROM aetheris_workflow","GRANT EXECUTE ON FUNCTION workflow.execution_identity_valid(jsonb) TO aetheris_workflow"),
                List.of("trigger-execute","GRANT EXECUTE ON FUNCTION workflow.guard_execution_definition() TO aetheris_workflow","REVOKE EXECUTE ON FUNCTION workflow.guard_execution_definition() FROM aetheris_workflow"),
                List.of("cross-schema-helper","GRANT EXECUTE ON FUNCTION workflow.execution_identity_valid(jsonb) TO aetheris_auth","REVOKE EXECUTE ON FUNCTION workflow.execution_identity_valid(jsonb) FROM aetheris_auth"),
                List.of("public-helper","GRANT EXECUTE ON FUNCTION workflow.execution_identity_valid(jsonb) TO PUBLIC","REVOKE EXECUTE ON FUNCTION workflow.execution_identity_valid(jsonb) FROM PUBLIC"));
            for(var mutation:mutations) {
                try {execute(c,mutation.get(1));try{verifyRuntime(c);throw new AssertionError("Bad function ACL passed verification");}catch(IllegalStateException expected){}}
                finally{execute(c,mutation.get(2));}
                verifyRuntime(c);results.add(Map.of("case","function-acl:"+mutation.get(0),"rejected",true,"passed",true));
            }
        }
        for(String schema:List.of("workflow","ingestion","quality"))try(Connection c=service(schema)) {
            denied(c,"SELECT "+q(schema)+".guard_execution_definition()",schema+":trigger-manual-execute");
            String other=schema.equals("workflow")?"quality":"workflow";
            denied(c,"SELECT "+q(other)+".execution_identity_valid('{}'::jsonb)",schema+":cross-schema-helper-execute");
        }
    }
    static void run() throws Exception {
        require(text(map(request.get("admin")),"url").matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_roles_audit"),"Disposable loopback database required");
        require(request.get("directories") instanceof Map<?,?>,"Current source directories required for acceptance verification");
        try(Connection c=admin()){require("aetheris_roles_audit".equals(scalar(c,"SELECT current_database()")),"Wrong database");}
        for(String schema:List.of("workflow","ingestion","quality"))source(schema);
        receipt();privilegeNegatives();
    }
}
