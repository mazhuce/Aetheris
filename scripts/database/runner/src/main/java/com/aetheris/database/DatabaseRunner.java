package com.aetheris.database;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Explicit tool process. Credentials arrive on stdin, never as command arguments. */
public final class DatabaseRunner {
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> SCHEMAS = List.of("auth","system","metadata","serving","workflow","ingestion","quality","governance","ai");
    static Map<String,Object> request;
    static String q(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    static String runtime(String schema) { if(!SCHEMAS.contains(schema)) throw new IllegalArgumentException("Unknown schema"); return "aetheris_"+schema; }
    static String text(Map<String,Object> value, String key) { return Objects.toString(value.get(key), ""); }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    static Connection connect(Map<String,Object> config) throws SQLException {
        String url=text(config,"url");
        if(!url.startsWith("jdbc:postgresql://") || url.matches("(?is).*[?&](password|user)=.*")) throw new IllegalArgumentException("Use PostgreSQL URL without credentials");
        Properties props=new Properties(); props.setProperty("user",text(config,"user")); props.setProperty("password",text(config,"password"));
        props.setProperty("connectTimeout","10"); props.setProperty("socketTimeout","60"); props.setProperty("ApplicationName","aetheris-database-roles");
        return DriverManager.getConnection(url,props);
    }
    static Connection admin() throws SQLException { return connect(map(request.get("admin"))); }
    static List<Map<String,Object>> rows(Connection c,String sql) throws SQLException {
        List<Map<String,Object>> rows=new ArrayList<>();
        try(var st=c.createStatement();var rs=st.executeQuery(sql)) {
            var md=rs.getMetaData(); while(rs.next()) { Map<String,Object> row=new LinkedHashMap<>();
                for(int n=1;n<=md.getColumnCount();n++) { Object v=rs.getObject(n); if(v instanceof java.sql.Array a) v=a.toString(); if(v instanceof Timestamp) v=v.toString(); row.put(md.getColumnLabel(n),v); } rows.add(row); }
        } return rows;
    }
    static Object scalar(Connection c,String sql) throws SQLException { try(var st=c.createStatement();var rs=st.executeQuery(sql)) { if(!rs.next())throw new IllegalStateException("No result"); return rs.getObject(1); } }
    static void execute(Connection c,String sql) throws SQLException { try(var st=c.createStatement()) { st.execute(sql); } }
    static String schemaList() { return "('auth','system','metadata','serving','workflow','ingestion','quality','governance','ai')"; }
    static Map<String,Object> inventory(Connection c) throws SQLException {
        Map<String,Object> result=new LinkedHashMap<>();
        var database=rows(c,"SELECT datname AS database,oid::text AS \"databaseOid\",pg_get_userbyid(datdba) AS \"databaseOwner\",datacl::text AS \"databaseAcl\" FROM pg_database WHERE datname=current_database()").getFirst(); result.putAll(database);
        result.put("systemIdentifier",Objects.toString(scalar(c,"SELECT system_identifier::text FROM pg_control_system()")));
        result.put("sourceHashes",request.get("sourceHashes"));
        result.put("publicSchema",rows(c,"SELECT pg_get_userbyid(nspowner) AS owner,nspacl::text AS acl,EXISTS(SELECT 1 FROM aclexplode(nspacl) WHERE grantee=0 AND privilege_type='CREATE') AS \"publicCreate\" FROM pg_namespace WHERE nspname='public'").getFirst());
        result.put("schemas",rows(c,"SELECT nspname AS name,pg_get_userbyid(nspowner) AS owner,nspacl::text AS acl FROM pg_namespace WHERE nspname IN "+schemaList()+" ORDER BY nspname"));
        result.put("relations",rows(c,"SELECT n.nspname AS schema,c.relname AS name,c.relkind::text AS kind,pg_get_userbyid(c.relowner) AS owner,c.relacl::text AS acl FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN "+schemaList()+" AND c.relkind NOT IN ('i','I','t','c') ORDER BY n.nspname,c.relkind,c.relname"));
        result.put("functions",rows(c,"SELECT n.nspname AS schema,p.proname AS name,pg_get_function_identity_arguments(p.oid) AS arguments,pg_get_function_result(p.oid) AS \"resultType\",pg_get_userbyid(p.proowner) AS owner,p.proacl::text AS acl,p.prosecdef AS \"securityDefiner\",md5(pg_get_functiondef(p.oid)) AS \"definitionMd5\" FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN "+schemaList()+" ORDER BY n.nspname,p.proname,p.oid"));
        result.put("types",rows(c,"SELECT n.nspname AS schema,t.typname AS name FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace LEFT JOIN pg_class c ON c.oid=t.typrelid WHERE n.nspname IN "+schemaList()+" AND (t.typtype IN ('d','e','r') OR c.relkind='c') ORDER BY n.nspname,t.typname"));
        result.put("extensions",rows(c,"SELECT e.extname AS name,n.nspname AS schema FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE n.nspname IN "+schemaList()+" ORDER BY e.extname"));
        result.put("defaultAcls",rows(c,"SELECT pg_get_userbyid(d.defaclrole) AS owner,n.nspname AS schema,d.defaclobjtype::text AS type,d.defaclacl::text AS acl FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid=d.defaclnamespace WHERE n.nspname IN "+schemaList()+" OR pg_get_userbyid(d.defaclrole) LIKE 'aetheris\\_%' ESCAPE '\\' ORDER BY d.defaclrole,d.defaclnamespace,d.defaclobjtype"));
        result.put("roles",rows(c,"SELECT rolname AS name,rolsuper,rolinherit,rolcreaterole,rolcreatedb,rolcanlogin,rolreplication,rolbypassrls,rolvaliduntil::text,rolconfig::text FROM pg_roles WHERE rolname LIKE 'aetheris\\_%' ESCAPE '\\' ORDER BY rolname"));
        result.put("memberships",rows(c,"SELECT p.rolname AS parent,m.rolname AS member,a.admin_option,a.inherit_option,a.set_option FROM pg_auth_members a JOIN pg_roles p ON p.oid=a.roleid JOIN pg_roles m ON m.oid=a.member WHERE p.rolname LIKE 'aetheris\\_%' ESCAPE '\\' OR m.rolname LIKE 'aetheris\\_%' ESCAPE '\\' ORDER BY p.rolname,m.rolname"));
        result.put("dependencies",rows(c,"""
            WITH objects AS (
              SELECT 'pg_class'::regclass::oid AS classid,oid,relnamespace AS ns FROM pg_class
              UNION ALL SELECT 'pg_proc'::regclass::oid,oid,pronamespace FROM pg_proc
              UNION ALL SELECT 'pg_type'::regclass::oid,oid,typnamespace FROM pg_type
              UNION ALL SELECT 'pg_rewrite'::regclass::oid,r.oid,c.relnamespace FROM pg_rewrite r JOIN pg_class c ON c.oid=r.ev_class
              UNION ALL SELECT 'pg_constraint'::regclass::oid,oid,connamespace FROM pg_constraint
            ) SELECT DISTINCT n.nspname AS "fromSchema",rn.nspname AS "toSchema",pg_describe_object(d.classid,d.objid,d.objsubid) AS object,pg_describe_object(d.refclassid,d.refobjid,d.refobjsubid) AS reference
            FROM pg_depend d JOIN objects o ON o.classid=d.classid AND o.oid=d.objid JOIN objects ro ON ro.classid=d.refclassid AND ro.oid=d.refobjid
            JOIN pg_namespace n ON n.oid=o.ns JOIN pg_namespace rn ON rn.oid=ro.ns
            WHERE n.nspname IN ('auth','system','metadata','serving','workflow','ingestion','quality','governance','ai')
              AND rn.nspname NOT IN ('pg_catalog','information_schema',n.nspname)
            ORDER BY "fromSchema","toSchema",object,reference
            """));
        Map<String,Object> history=new TreeMap<>();
        for(String s:SCHEMAS) if(scalar(c,"SELECT to_regclass("+literal(s+".flyway_schema_history")+")")!=null)
            history.put(s,rows(c,"SELECT installed_rank,version,description,type,script,checksum,installed_by,installed_on::text,success FROM "+q(s)+".flyway_schema_history ORDER BY installed_rank"));
        result.put("history",history);
        Map<String,Object> validation=new TreeMap<>();
        if(request.containsKey("directories"))for(String s:history.keySet()) {
            var conf=map(request.get("admin"));String service=s.equals("ai")?"ai-agent":s;
            var f=Flyway.configure().dataSource(text(conf,"url"),text(conf,"user"),text(conf,"password"))
                .schemas(s).defaultSchema(s).createSchemas(false).cleanDisabled(true).ignoreMigrationPatterns(new String[0])
                .locations("filesystem:"+text(map(request.get("directories")),service)).load();
            var checked=f.validateWithResult();
            Set<String> names=new HashSet<>();
            try(var files=Files.list(Path.of(text(map(request.get("directories")),service)))) {files.forEach(p->names.add(p.getFileName().toString()));}
            catch(java.io.IOException e){throw new IllegalStateException("Source migration directory missing");}
            boolean scriptsMatch=true;
            @SuppressWarnings("unchecked") var applied=(List<Map<String,Object>>)history.get(s);
            for(var row:applied)if(!Objects.equals(row.get("type"),"SCHEMA") && (!Objects.equals(row.get("type"),"SQL") || !names.contains(text(row,"script")) || row.get("checksum")==null))scriptsMatch=false;
            validation.put(s,Map.of("validationSuccessful",checked.validationSuccessful && scriptsMatch,"scriptsMatch",scriptsMatch,"pending",f.info().pending().length));
        }
        result.put("sourceValidation",validation);return result;
    }
    static void checkRoles(Connection c) throws SQLException {
        for(String schema:SCHEMAS) for(String suffix:List.of("","_owner","_migrate")) {
            String name=runtime(schema)+suffix;
            var found=rows(c,"SELECT rolsuper,rolcreaterole,rolcreatedb,rolreplication,rolbypassrls,rolinherit FROM pg_roles WHERE rolname="+literal(name));
            if(!found.isEmpty() && found.getFirst().values().stream().anyMatch(Boolean.TRUE::equals)) throw new IllegalStateException("Existing privileged role requires manual review: "+name);
            if(!suffix.isEmpty() && Boolean.TRUE.equals(scalar(c,"SELECT coalesce((SELECT rolcanlogin FROM pg_roles WHERE rolname="+literal(name)+"),false)")))throw new IllegalStateException("Owner or migration account must be retired before provisioning");
            if(((Number)scalar(c,"SELECT count(*) FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.member WHERE r.rolname="+literal(name))).longValue()!=0) throw new IllegalStateException("Unexpected role membership: "+name);
        }
    }
    static String credential(String schema,String kind) {
        String value=text(map(map(request.get("credentials")).get(schema)),kind);
        if(!value.matches("[a-zA-Z0-9_-]{32,128}")) throw new IllegalArgumentException("Each test/runtime/migration secret must be 32-128 random URL-safe characters"); return value;
    }
    static void provision(Connection c,String bootstrap) throws SQLException {
        checkRoles(c); execute(c,bootstrap);
        for(String s:SCHEMAS) execute(c,"ALTER ROLE "+q(runtime(s))+" LOGIN PASSWORD "+literal(credential(s,"runtime")));
    }
    static void baseline(Connection c) throws SQLException {
        String db=Objects.toString(scalar(c,"SELECT current_database()"));
        execute(c,"REVOKE CREATE, TEMPORARY ON DATABASE "+q(db)+" FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        for(String s:SCHEMAS)execute(c,"GRANT CONNECT ON DATABASE "+q(db)+" TO "+q(runtime(s))+"; ALTER ROLE "+q(runtime(s))+" IN DATABASE "+q(db)+" SET search_path=pg_catalog,"+q(s));
    }
    static Map<String,Object> provisionEmpty(Connection c) throws SQLException {
        c.setAutoCommit(false);
        if(((Number)scalar(c,"SELECT count(*) FROM pg_namespace WHERE nspname NOT IN ('pg_catalog','information_schema','public') AND nspname NOT LIKE 'pg_toast%' AND nspname NOT LIKE 'pg_temp%'")).longValue()!=0 || ((Number)scalar(c,"SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace")).longValue()!=0 || !rows(c,"SELECT rolname FROM pg_roles WHERE rolname LIKE 'aetheris\\_%' ESCAPE '\\'").isEmpty()) throw new IllegalStateException("Empty database/unused target roles required; use reviewed inventory plan for existing installations");
        provision(c,text(request,"sql"));baseline(c);c.commit();return Map.of("provisioned",true,"emptyDatabaseVerified",true);
    }
    static void activate(Connection c,String schema) throws SQLException {
        String r=runtime(schema)+"_migrate";
        execute(c,"ALTER ROLE "+q(r)+" LOGIN VALID UNTIL "+literal(Instant.now().plusSeconds(900).toString())+" PASSWORD "+literal(credential(schema,"migration")));
        execute(c,"GRANT "+q(runtime(schema)+"_owner")+" TO "+q(r)+" WITH INHERIT FALSE, SET TRUE, ADMIN FALSE");
    }
    static void retire(Connection c,String schema) throws SQLException {
        String r=runtime(schema)+"_migrate";
        if(((Number)scalar(c,"SELECT count(*) FROM pg_roles WHERE rolname="+literal(r))).longValue()==0)return;
        execute(c,"ALTER ROLE "+q(r)+" NOLOGIN PASSWORD NULL VALID UNTIL 'epoch'");
        execute(c,"REVOKE "+q(runtime(schema)+"_owner")+" FROM "+q(r));
        scalar(c,"SELECT count(*) FROM (SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename="+literal(r)+" AND pid<>pg_backend_pid()) x");
        checkRetired(c,schema);
    }
    static void checkRetired(Connection c,String schema) throws SQLException {
        String r=runtime(schema)+"_migrate";
        var state=rows(c,"SELECT NOT rolcanlogin AND rolpassword IS NULL AS retired FROM pg_authid WHERE rolname="+literal(r)).getFirst();
        if(!Boolean.TRUE.equals(state.get("retired")) || ((Number)scalar(c,"SELECT count(*) FROM pg_stat_activity WHERE usename="+literal(r))).longValue()!=0 || ((Number)scalar(c,"SELECT count(*) FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.member WHERE r.rolname="+literal(r))).longValue()!=0) throw new IllegalStateException("Migration credential retirement incomplete");
    }
    static Map<String,Object> verifyRuntime(Connection c) throws Exception {
        Map<String,Object> adminConfig=map(request.get("admin"));
        Map<String,Object> verified=new LinkedHashMap<>();
        var functions=rows(c,"SELECT p.oid,n.nspname AS schema,p.proname AS name,pg_get_function_identity_arguments(p.oid) AS arguments,pg_get_function_result(p.oid) AS \"resultType\",p.prosecdef AS \"securityDefiner\",pg_get_userbyid(p.proowner) AS owner FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN "+schemaList());
        ReviewedFunctions.inventory(functions);
        for(String s:SCHEMAS) {
            checkRetired(c,s);
            var roles=rows(c,"SELECT rolsuper OR rolinherit OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls AS dangerous FROM pg_roles WHERE rolname="+literal(runtime(s)));
            if(roles.size()!=1 || Boolean.TRUE.equals(roles.getFirst().get("dangerous")))throw new IllegalStateException("Unsafe runtime role attributes");
            if(((Number)scalar(c,"SELECT count(*) FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.member WHERE r.rolname="+literal(runtime(s)))).longValue()!=0)throw new IllegalStateException("Runtime role inherited membership");
            Map<String,Object> conf=new HashMap<>(adminConfig);conf.put("user",runtime(s));conf.put("password",credential(s,"runtime"));
            try(Connection runtimeConnection=connect(conf)) {
                runtimeConnection.setReadOnly(true);
                if(Boolean.TRUE.equals(scalar(runtimeConnection,"SELECT has_database_privilege(current_database(),'CREATE') OR has_database_privilege(current_database(),'TEMP') OR has_schema_privilege('public','CREATE')")))throw new IllegalStateException("Runtime can create DDL");
                for(String other:SCHEMAS) {
                    boolean usage=Boolean.TRUE.equals(scalar(runtimeConnection,"SELECT has_schema_privilege("+literal(other)+",'USAGE')"));
                    boolean create=Boolean.TRUE.equals(scalar(runtimeConnection,"SELECT has_schema_privilege("+literal(other)+",'CREATE')"));
                    if(usage!=other.equals(s) || create)throw new IllegalStateException("Runtime schema boundary mismatch");
                }
                for(var row:rows(c,"SELECT c.relname AS name,c.relkind::text AS kind,pg_get_userbyid(c.relowner) AS owner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname="+literal(s)+" AND c.relkind IN ('r','p','S','v','m') ORDER BY c.relname")) {
                    String name=s+"."+row.get("name");
                    if(!Objects.equals(row.get("owner"),runtime(s)+"_owner"))throw new IllegalStateException("Object owner mismatch");
                    String function=row.get("kind").equals("S")?"has_sequence_privilege":"has_table_privilege";
                    List<String> wanted=row.get("kind").equals("S")?List.of("USAGE","SELECT"):row.get("name").equals("flyway_schema_history")?List.of():List.of("SELECT","INSERT","UPDATE","DELETE");
                    List<String> denied=row.get("kind").equals("S")?List.of("UPDATE"):row.get("name").equals("flyway_schema_history")?List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER"):List.of("TRUNCATE","REFERENCES","TRIGGER");
                    for(String p:wanted)if(!Boolean.TRUE.equals(scalar(runtimeConnection,"SELECT "+function+"("+literal(name)+","+literal(p)+")")))throw new IllegalStateException("Missing runtime privilege");
                    for(String p:denied)if(Boolean.TRUE.equals(scalar(runtimeConnection,"SELECT "+function+"("+literal(name)+","+literal(p)+")")))throw new IllegalStateException("Unexpected runtime privilege");
                }
                for(var function:functions) {
                    var policy=ReviewedFunctions.review(function);String schema=text(function,"schema");
                    if(!Objects.equals(function.get("owner"),runtime(schema)+"_owner"))throw new IllegalStateException("Function owner mismatch");
                    boolean actual=Boolean.TRUE.equals(scalar(c,"SELECT has_function_privilege("+literal(runtime(s))+","+function.get("oid")+",'EXECUTE')"));
                    if(actual!=(policy.runtimeExecute() && schema.equals(s)))throw new IllegalStateException("Missing or unexpected runtime function privilege");
                }
            }
            String service=s.equals("ai")?"ai-agent":s;
            String path=text(map(request.get("directories")),service);
            Flyway f=Flyway.configure().dataSource(text(adminConfig,"url"),text(adminConfig,"user"),text(adminConfig,"password"))
                    .schemas(s).defaultSchema(s).createSchemas(false).cleanDisabled(true).ignoreMigrationPatterns(new String[0])
                    .initSql("SET ROLE "+q(runtime(s)+"_owner")).locations("filesystem:"+Path.of(path).toAbsolutePath()).load();
            if(!f.validateWithResult().validationSuccessful || f.info().pending().length!=0)throw new IllegalStateException("Current source/Flyway history mismatch");
            verified.put(s,Map.of("runtimeLogin",true,"permissions",true,"historyValidated",true,"migrationRetired",true));
        }
        return Map.of("verified",verified,"status","passed");
    }
    static Map<String,Object> legacyFixture() throws Exception {
        String url=text(map(request.get("admin")),"url");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_roles_audit"))throw new IllegalStateException("Disposable fixture database required");
        Map<String,Object> results=new LinkedHashMap<>();var adminConfig=map(request.get("admin"));
        for(var entry:map(request.get("directories")).entrySet()) {
            String schema=entry.getKey().equals("ai-agent")?"ai":entry.getKey();runtime(schema);
            var f=Flyway.configure().dataSource(url,text(adminConfig,"user"),text(adminConfig,"password"))
                    .schemas(schema).defaultSchema(schema).cleanDisabled(true).locations("filesystem:"+entry.getValue()).load();
            results.put(schema,Map.of("executed",f.migrate().migrationsExecuted,"validated",f.validateWithResult().validationSuccessful));
        }return results;
    }
    static Map<String,Object> migrate(String schema,String directory) throws Exception {
        Map<String,Object> config=map(request.get("admin"));
        try { Flyway f=Flyway.configure().dataSource(text(config,"url"),runtime(schema)+"_migrate",credential(schema,"migration"))
                .schemas(schema).defaultSchema(schema).createSchemas(false).cleanDisabled(true).validateOnMigrate(true)
                .initSql("SET ROLE "+q(runtime(schema)+"_owner"))
                .locations("filesystem:"+Path.of(directory).toAbsolutePath()).load();
            try(Connection c=admin()) { c.setAutoCommit(false);activate(c,schema);c.commit(); }
            var result=f.migrate(); if(!f.validateWithResult().validationSuccessful || f.info().pending().length!=0)throw new IllegalStateException("Migration validation failed");
            return Map.of("schema",schema,"migrationsExecuted",result.migrationsExecuted,"pending",f.info().pending().length,"validated",true);
        } finally { try(Connection c=admin()) { retire(c,schema); } }
    }
    static Map<String,Object> retireAll(Connection c) throws SQLException {
        List<String> failed=new ArrayList<>();
        for(String s:SCHEMAS)try {retire(c,s);}catch(Exception e){failed.add(s);}
        if(!failed.isEmpty())throw new IllegalStateException("Migration credential retirement incomplete for "+failed.size()+" schema(s); run recovery again");
        return Map.of("retired",true,"schemas",SCHEMAS.size());
    }
    static Map<String,Object> applyPlan(Connection c) throws Exception {
        c.setAutoCommit(false);
        scalar(c,"SELECT pg_advisory_xact_lock(173601,hashtext(current_database()))");
        if(!JSON.valueToTree(inventory(c)).equals(JSON.valueToTree(request.get("expectedInventory")))) throw new IllegalStateException("Inventory changed; create a new reviewed plan");
        if(((Number)scalar(c,"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND backend_type='client backend' AND pid<>pg_backend_pid()")).longValue()!=0) throw new IllegalStateException("Drain all other database client sessions before applying the plan");
        provision(c,text(request,"bootstrapSql"));
        execute(c,text(request,"sql"));c.commit();return Map.of("applied",true,"inventoryMatched",true,"otherClientSessions",0);
    }
    public static void main(String[] args) {
        try {
            if(args.length==2 && args[0].equals("--config"))request=JSON.readValue(Files.readString(Path.of(args[1]),StandardCharsets.UTF_8),new TypeReference<>(){});
            else if(args.length==0)request=JSON.readValue(System.in,new TypeReference<>(){});
            else throw new IllegalArgumentException("Use stdin or --config file");
            Map<String,Object> adminConfig=map(request.get("admin"));
            if(!adminConfig.containsKey("password") && adminConfig.containsKey("passwordFile"))adminConfig.put("password",Files.readString(Path.of(text(adminConfig,"passwordFile")),StandardCharsets.UTF_8).strip());
            if(request.containsKey("credentials"))for(Object item:map(request.get("credentials")).values())for(String kind:List.of("runtime","migration")) {
                Map<String,Object> value=map(item);if(!value.containsKey(kind) && value.containsKey(kind+"File"))value.put(kind,Files.readString(Path.of(text(value,kind+"File")),StandardCharsets.UTF_8).strip());
            }
            String command=text(request,"command"); Object result;
            if(command.equals("migrate")) result=migrate(text(request,"schema"),text(request,"directory"));
            else if(command.equals("acceptance")) result=DatabaseRolesIT.run();
            else if(command.equals("legacy-fixture")) result=legacyFixture();
            else try(Connection c=admin()) {
                result=switch(command) {
                    case "inventory" -> { c.setAutoCommit(false);c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); yield inventory(c); }
                    case "provision" -> { c.setAutoCommit(false);provision(c,text(request,"sql"));c.commit();yield Map.of("provisioned",true); }
                    case "provision-empty" -> provisionEmpty(c);
                    case "apply-plan" -> applyPlan(c);
                    case "verify" -> verifyRuntime(c);
                    case "retire" -> retireAll(c);
                    case "sql" -> { execute(c,text(request,"sql"));yield Map.of("applied",true); }
                    default -> throw new IllegalArgumentException("Unknown explicit command");
                };
            }
            System.out.println("AETHERIS_RESULT="+JSON.writeValueAsString(result));
        } catch(Exception e) {
            // Avoid echoing JDBC URLs, SQL, credentials or Flyway exception bodies.
            System.err.println("Database operation failed: "+e.getClass().getSimpleName());
            String message=Objects.toString(e.getMessage(),"");
            try {System.out.println("AETHERIS_ERROR="+JSON.writeValueAsString(Map.of("errorClass",e.getClass().getSimpleName(),
                "duplicateVersion",e instanceof FlywayException && message.contains("Found more than one migration with version"),
                "v3HistoryMismatch",e instanceof FlywayException && (message.contains("checksum mismatch for migration version 3") || message.contains("description mismatch for migration version 3")))));}catch(Exception ignored){}
            System.exit(1);
        }
    }
}
