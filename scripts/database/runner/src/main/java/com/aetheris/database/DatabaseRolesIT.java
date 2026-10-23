package com.aetheris.database;

import java.sql.*;
import java.util.*;
import static com.aetheris.database.DatabaseRunner.*;

/** Real PostgreSQL checks, invoked only by the disposable-container wrapper. */
final class DatabaseRolesIT {
    private static final Map<String,String> TABLES=Map.of("auth","auth_user","system","system_config","metadata","data_source","serving","bi_dashboard_template","workflow","task","ingestion","ingestion_task","quality","quality_rule","governance","governance_policy","ai","chat_conversation");
    static final List<Map<String,Object>> results=new ArrayList<>();
    static void require(boolean condition,String message) { if(!condition)throw new IllegalStateException(message); }
    static void denied(Connection c,String sql,String label) throws Exception {
        try { execute(c,sql); throw new IllegalStateException("Forbidden operation succeeded: "+label); }
        catch(SQLException e) { require("42501".equals(e.getSQLState()),"Expected insufficient_privilege for "+label+", got "+e.getSQLState()); results.add(Map.of("case",label,"sqlState",e.getSQLState(),"passed",true)); }
    }
    static Connection service(String schema) throws SQLException {
        Map<String,Object> config=new HashMap<>(map(request.get("admin")));
        config.put("user",runtime(schema));config.put("password",credential(schema,"runtime"));return connect(config);
    }
    static String insert(String s) {
        return switch(s) {
            case "auth" -> "INSERT INTO auth.auth_user(username,password_hash,nickname) VALUES('db_roles_it','test-hash','before') RETURNING id";
            case "system" -> "INSERT INTO system.system_config(config_key,config_value,description) VALUES('db_roles_it','test','before') RETURNING id";
            case "metadata" -> "INSERT INTO metadata.data_source(name,type,host,username,description) VALUES('db_roles_it','postgresql','127.0.0.1','readonly','before') RETURNING id";
            case "serving" -> "INSERT INTO serving.bi_dashboard_template(code,name,category,spec_json) VALUES('db_roles_it','before','test','{}') RETURNING id";
            case "workflow" -> "INSERT INTO workflow.task(name,datasource_id,task_sql,description) VALUES('db_roles_it',1,'SELECT 1','before') RETURNING id";
            case "ingestion" -> "INSERT INTO ingestion.ingestion_task(name,source_type,target_table,description) VALUES('db_roles_it','jdbc','warehouse.test','before') RETURNING id";
            case "quality" -> "INSERT INTO quality.quality_rule(name,rule_type,target_table,description) VALUES('db_roles_it','not_null','warehouse.test','before') RETURNING id";
            case "governance" -> "INSERT INTO governance.governance_policy(name,policy_type,target_table,description) VALUES('db_roles_it','masking','warehouse.test','before') RETURNING id";
            case "ai" -> "INSERT INTO ai.chat_conversation(title,owner_username) VALUES('before','db_roles_it') RETURNING id";
            default -> throw new IllegalArgumentException();
        };
    }
    static void crud(String s) throws Exception {
        try(Connection c=service(s)) {
            c.setAutoCommit(false);
            String table=q(s)+"."+q(TABLES.get(s));long id=((Number)scalar(c,insert(s))).longValue(); require(id>0,"Generated sequence id");
            String column=s.equals("auth")?"nickname":s.equals("ai")?"title":s.equals("serving")?"name":"description";
            execute(c,"UPDATE "+table+" SET "+q(column)+"='after' WHERE id="+id);
            require("after".equals(scalar(c,"SELECT "+q(column)+" FROM "+table+" WHERE id="+id)),"Runtime update/read");
            if(List.of("ingestion","quality").contains(s)) {
                long first=((Number)scalar(c,"SELECT generation FROM "+q(s)+".scheduler_sync_outbox WHERE resource_id="+id)).longValue();
                execute(c,"UPDATE "+table+" SET name='db_roles_it_changed' WHERE id="+id);
                long second=((Number)scalar(c,"SELECT generation FROM "+q(s)+".scheduler_sync_outbox WHERE resource_id="+id)).longValue();
                require(second>first,"Invoker outbox trigger and sequence");
            }
            if(s.equals("metadata")) {
                execute(c,"UPDATE metadata.data_source SET host='127.0.0.2' WHERE id="+id);
                require(((Number)scalar(c,"SELECT connection_revision FROM metadata.data_source WHERE id="+id)).longValue()==1,"Health invalidation trigger");
            }
            execute(c,"DELETE FROM "+table+" WHERE id="+id);
            require(((Number)scalar(c,"SELECT count(*) FROM "+table+" WHERE id="+id)).intValue()==0,"Runtime delete");
            if(List.of("ingestion","quality").contains(s)) require(Boolean.TRUE.equals(scalar(c,"SELECT deleted FROM "+q(s)+".scheduler_sync_outbox WHERE resource_id="+id)),"Delete outbox tombstone");
            c.rollback();
        }
        if(List.of("workflow","ingestion","quality").contains(s)) try(Connection c=service(s)) {
            c.setAutoCommit(false);String t=q(s)+".qrtz_locks";
            execute(c,"INSERT INTO "+t+"(sched_name,lock_name) VALUES('DatabaseRolesIT','lock')");
            scalar(c,"SELECT lock_name FROM "+t+" WHERE sched_name='DatabaseRolesIT' FOR UPDATE");
            execute(c,"UPDATE "+t+" SET lock_name='updated' WHERE sched_name='DatabaseRolesIT'");
            execute(c,"DELETE FROM "+t+" WHERE sched_name='DatabaseRolesIT'");c.rollback();
        }
        results.add(Map.of("case",s+":actual-table-crud-sequence-triggers","passed",true));
    }
    static Map<String,Object> run() throws Exception {
        String url=text(map(request.get("admin")),"url");
        require(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_roles_audit"),"Disposable loopback database required");
        try(Connection a=admin()) { require("aetheris_roles_audit".equals(scalar(a,"SELECT current_database()")),"Wrong database"); }
        for(String s:SCHEMAS) {
            crud(s);
            try(Connection c=service(s)) {
                for(String other:SCHEMAS) if(!s.equals(other)) denied(c,"SELECT * FROM "+q(other)+"."+q(TABLES.get(other))+" LIMIT 1",s+":cross-schema:"+other);
                denied(c,"SELECT rolpassword FROM pg_authid",s+":admin-password-catalog");
                denied(c,"SELECT passwd FROM pg_shadow",s+":shadow-password-catalog");
                denied(c,"SELECT pg_read_file('/etc/passwd')",s+":server-file");
                denied(c,"SET ROLE audit_admin",s+":admin-role");
                denied(c,"SET ROLE "+q(runtime(s)+"_owner"),s+":owner-role");
                denied(c,"SET ROLE "+q(runtime(s)+"_migrate"),s+":migration-role");
                denied(c,"CREATE TABLE "+q(s)+".forbidden(id int)",s+":create-own-table");
                denied(c,"CREATE TABLE public.forbidden(id int)",s+":create-public-table");
                denied(c,"CREATE TEMP TABLE forbidden(id int)",s+":temporary-ddl");
                denied(c,"CREATE SCHEMA forbidden",s+":create-schema");
                denied(c,"CREATE ROLE forbidden",s+":create-role");
                denied(c,"ALTER TABLE "+q(s)+"."+q(TABLES.get(s))+" ADD COLUMN forbidden int",s+":alter-own-table");
                denied(c,"TRUNCATE "+q(s)+"."+q(TABLES.get(s)),s+":truncate");
                denied(c,"SELECT * FROM "+q(s)+".flyway_schema_history",s+":history-read");
                denied(c,"DELETE FROM "+q(s)+".flyway_schema_history",s+":history-write");
                try(Connection a=admin()) {
                    var seq=rows(a,"SELECT c.relname AS name FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname="+literal(s)+" AND c.relkind='S' ORDER BY c.relname");
                    for(var row:seq) { String name=s+"."+row.get("name"); require(((Number)scalar(c,"SELECT nextval("+literal(name)+")")).longValue()>0,"nextval"); denied(c,"SELECT setval("+literal(name)+",1)",s+":setval:"+row.get("name")); }
                }
            }
            try(Connection a=admin()) {
                retire(a,s);
                require(((Number)scalar(a,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname="+literal(s)+" AND pg_get_userbyid(c.relowner)<>"+literal(runtime(s)+"_owner"))).longValue()==0,"Objects must retain NOLOGIN owner");
                execute(a,"SET ROLE "+q(runtime(s)+"_owner"));
                String other=SCHEMAS.get((SCHEMAS.indexOf(s)+1)%SCHEMAS.size());
                denied(a,"CREATE TABLE "+q(other)+".forbidden(id int)",s+":migration-owner-cross-schema");execute(a,"RESET ROLE");
            }
            Map<String,Object> retired=new HashMap<>(map(request.get("admin")));retired.put("user",runtime(s)+"_migrate");retired.put("password",credential(s,"migration"));
            try(Connection ignored=connect(retired)) { throw new IllegalStateException("Retired migrator connected"); }
            catch(SQLException e) { require(Set.of("28000","28P01").contains(e.getSQLState()),"Expected retired login denial");results.add(Map.of("case",s+":migration-login-retired","sqlState",e.getSQLState(),"passed",true)); }
        }
        FixedExecutionRuntimeIT.run();
        return Map.of("status","passed","checks",results,"schemas",SCHEMAS.size());
    }
}
