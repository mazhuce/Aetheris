package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.*;
import com.aetheris.security.protocol.BodyHash;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit opt-in, disposable loopback audit database only; never selected by ordinary Surefire. */
class FixedSqlDurabilityPostgresIT {
    static JdbcTemplate admin(){
        String url=System.getenv("AETHERIS_TEST_FIXED_SQL_URL"),password=System.getenv("AETHERIS_TEST_FIXED_SQL_PASSWORD");assertNotNull(url);var uri=URI.create(url.substring(5));assertEquals("127.0.0.1",uri.getHost());assertTrue(uri.getPort()>0);assertEquals("/aetheris_fixed_sql_audit",uri.getPath());assertNotNull(password);assertTrue(password.matches("[A-Za-z0-9_-]{32,}"));return new JdbcTemplate(new DriverManagerDataSource(url,"audit_admin",password));
    }
    static String random(){return Base64.getUrlEncoder().withoutPadding().encodeToString(new java.security.SecureRandom().generateSeed(32));}
    static void until(BooleanSupplier predicate)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!predicate.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertTrue(predicate.getAsBoolean());}
    @Test void recursiveKnownDefinitionEncodingEqualsPostgresBytesAndHash(){
        var db=admin();var definitions=new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
        var base=FixedSqlTestPeers.definition(8,"INSERT INTO t SELECT '${bizDate}', '中文😀 / \\\"\\b\\f\\n\\r\\t'",86400);definitions.add(base);
        var nested=base.deepCopy();nested.set("dependencies",JSON.createObjectNode().set("upstreamTaskIds",JSON.createArrayNode().add(1).add(23)));definitions.add(nested);
        var controls=base.deepCopy();controls.put("sql","中文😀/\"\\\b\f\n\r\t"+(char)31);definitions.add(controls);
        for(var value:definitions){String pg=db.queryForObject("SELECT ?::jsonb::text",String.class,string(value));assertEquals(pg,FixedSqlJsonb.encode(value));assertEquals(db.queryForObject("SELECT encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex')",String.class,string(value)),FixedSqlJsonb.hash(value));}
        assertThrows(IllegalArgumentException.class,()->FixedSqlJsonb.encode(JSON.createObjectNode().put("sql","\u0000")));assertThrows(IllegalArgumentException.class,()->FixedSqlJsonb.encode(JSON.createObjectNode().put("sql","\ud800")));
    }
    @Test void runtimeReceiptAndIndependentWriterDispatchAtMostOnceAfterLostOutcome()throws Exception{
        var admin=admin();String url=System.getenv("AETHERIS_TEST_FIXED_SQL_URL"),password=System.getenv("AETHERIS_TEST_FIXED_SQL_PASSWORD");var endpoint=URI.create(url.substring(5));
        var flyway=Flyway.configure().dataSource(url,"audit_admin",password).schemas("metadata").defaultSchema("metadata").locations("classpath:db/migration").load();assertTrue(flyway.migrate().migrationsExecuted>0);assertTrue(flyway.validateWithResult().validationSuccessful);assertEquals(0,flyway.info().pending().length);
        String runtimeSecret=random(),readSecret=random(),writeSecret=random();
        admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");for(var role:Map.of("sql_runtime",runtimeSecret,"sql_reader",readSecret,"sql_writer",writeSecret).entrySet())admin.execute("CREATE ROLE "+role.getKey()+" LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+role.getValue()+"'");
        admin.execute("GRANT USAGE ON SCHEMA metadata TO sql_runtime; GRANT SELECT,INSERT,UPDATE ON metadata.execution_receipt TO sql_runtime; GRANT USAGE,SELECT ON SEQUENCE metadata.execution_receipt_id_seq TO sql_runtime; GRANT SELECT ON metadata.data_source TO sql_runtime");
        admin.execute("CREATE SCHEMA business; CREATE TABLE business.sql_output(id BIGINT,actor TEXT); GRANT USAGE ON SCHEMA business TO sql_reader,sql_writer; GRANT SELECT ON business.sql_output TO sql_reader; GRANT INSERT ON business.sql_output TO sql_writer");
        var runtime=new JdbcTemplate(new DriverManagerDataSource(url,"sql_runtime",runtimeSecret));var reader=new JdbcTemplate(new DriverManagerDataSource(url,"sql_reader",readSecret));var writer=new JdbcTemplate(new DriverManagerDataSource(url,"sql_writer",writeSecret));
        assertEquals("sql_runtime",runtime.queryForObject("SELECT current_user",String.class));assertFalse(runtime.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.queryForObject("SELECT count(*) FROM business.sql_output",Long.class));assertThrows(org.springframework.dao.DataAccessException.class,()->reader.update("INSERT INTO business.sql_output SELECT 999,'reader'"));assertThrows(org.springframework.dao.DataAccessException.class,()->writer.queryForObject("SELECT count(*) FROM business.sql_output",Long.class));
        String master=Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));var secrets=new DataSourceSecretService(master);
        long ds=admin.queryForObject("INSERT INTO metadata.data_source(name,type,host,port,database_name,username,password,write_username,write_password,status,managed) VALUES('fixed-sql-writer','postgresql','127.0.0.1',?,'aetheris_fixed_sql_audit','sql_reader',?,'sql_writer',?,'active',false) RETURNING id",Long.class,endpoint.getPort(),secrets.encrypt(readSecret),secrets.encrypt(writeSecret));
        var sources=mock(DataSourceService.class);when(sources.getById(any())).thenAnswer(call->runtime.queryForObject("SELECT * FROM metadata.data_source WHERE id=?",(r,i)->{var d=new DataSource();d.setId(r.getLong("id"));d.setType(r.getString("type"));d.setHost(r.getString("host"));d.setPort(r.getInt("port"));d.setDatabaseName(r.getString("database_name"));d.setUsername(r.getString("username"));d.setPassword(r.getString("password"));d.setWriteUsername(r.getString("write_username"));d.setWritePassword(r.getString("write_password"));d.setStatus(r.getString("status"));d.setManaged(r.getBoolean("managed"));d.setConnectionRevision(r.getLong("connection_revision"));return d;},(Object)call.getArgument(0)));
        var calls=new AtomicInteger();var loseOutcome=new AtomicBoolean();JdbcService jdbc=new JdbcService(secrets,false,new OutboundTargetPolicy()){
            @Override public Map<String,Object> execute(DataSource source,String sql,Integer timeout,Instant deadline){calls.incrementAndGet();var result=super.execute(source,sql,timeout,deadline);if(loseOutcome.get())throw new IllegalStateException("Fixture drops outcome after committed JDBC");return result;}
            @Override public Map<String,Object> execute(DataSource source,String sql,Integer timeout){throw new AssertionError("Unbounded legacy hook");}
        };
        var store=new FixedReceiptStore(runtime);try(var peers=new FixedSqlTestPeers()){
            var adapters=FixedExecutionAdapters.sqlOnly(peers.peers,new FixedSqlExecution(sources,jdbc));var coordinator=new FixedTransferCoordinator(store,adapters,Duration.ofMinutes(30));
            try(var worker=new FixedTransferWorker(store,adapters,1);var replica=new FixedTransferWorker(new FixedReceiptStore(runtime),adapters,1)){
                java.util.function.UnaryOperator<String> canonical=s->admin.queryForObject("SELECT ?::jsonb::text",String.class,s);
                var manifest=peers.install(11,ds,"INSERT INTO business.sql_output SELECT 11,CURRENT_USER",30,canonical);byte[] submit=bytes(peers.input(manifest));
                try(var threads=Executors.newFixedThreadPool(2)){var first=threads.submit(()->coordinator.submit(FixedExecutionOperation.SQL,submit));var second=threads.submit(()->coordinator.submit(FixedExecutionOperation.SQL,submit));assertEquals(first.get(10,TimeUnit.SECONDS).receiptId(),second.get(10,TimeUnit.SECONDS).receiptId());var a=threads.submit(()->{worker.tick();return true;});var b=threads.submit(()->{replica.tick();return true;});a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);}
                await(store,11,"succeeded",worker,replica);assertEquals(1,calls.get());assertEquals("sql_writer",reader.queryForObject("SELECT actor FROM business.sql_output WHERE id=11",String.class));var receipt=coordinator.status(FixedExecutionOperation.SQL,status(11));assertEquals(1,((FixedSqlResult)receipt.result()).affectedRows());assertEquals(9,parse(bytes(receipt)).size());
                var row=store.find(FixedExecutionOperation.SQL,11,111,3).orElseThrow();long lifetime=admin.queryForObject("SELECT extract(epoch from deadline_at-created_at)::bigint FROM metadata.execution_receipt WHERE id=?",Long.class,row.id());assertEquals(150,lifetime);var originalDeadline=row.deadline();assertEquals("succeeded",coordinator.submit(FixedExecutionOperation.SQL,submit).state());assertEquals(originalDeadline,store.find(FixedExecutionOperation.SQL,11,111,3).orElseThrow().deadline());
                peers.allowed.set(false);peers.sourceActive.set(false);assertEquals("succeeded",coordinator.submit(FixedExecutionOperation.SQL,submit).state());assertEquals(1,calls.get());peers.allowed.set(true);peers.sourceActive.set(true);
                assertThrows(FixedReceiptStore.Conflict.class,()->coordinator.submit(FixedExecutionOperation.SQL,bytes(new FixedExecutionAdapter.Submission(11,111,3,3,manifest.sha256()))));
                var changed=peers.install(12,ds,"INSERT INTO business.sql_output SELECT 12,CURRENT_USER",30,canonical);coordinator.submit(FixedExecutionOperation.SQL,bytes(peers.input(changed)));admin.update("UPDATE metadata.data_source SET write_password=? WHERE id=?",secrets.encrypt(random()),ds);worker.tick();await(store,12,"failed",worker,replica);assertEquals(1,calls.get());admin.update("UPDATE metadata.data_source SET write_password=? WHERE id=?",secrets.encrypt(writeSecret),ds);
                var lost=peers.install(13,ds,"INSERT INTO business.sql_output SELECT 13,CURRENT_USER",30,canonical);byte[] lostSubmit=bytes(peers.input(lost));coordinator.submit(FixedExecutionOperation.SQL,lostSubmit);loseOutcome.set(true);worker.tick();await(store,13,"unknown",worker,replica);assertEquals(2,calls.get());assertEquals(1,reader.queryForObject("SELECT count(*) FROM business.sql_output WHERE id=13",Integer.class));assertNull(coordinator.status(FixedExecutionOperation.SQL,status(13)).result());assertEquals("unknown",coordinator.submit(FixedExecutionOperation.SQL,lostSubmit).state());
                try(var restarted=new FixedTransferWorker(store,adapters,1)){restarted.tick();Thread.sleep(150);assertEquals(0,restarted.physicalActive());assertEquals(2,calls.get());}
                var denied=peers.install(14,ds,"INSERT INTO business.sql_output SELECT 14,CURRENT_USER",30,canonical);coordinator.submit(FixedExecutionOperation.SQL,bytes(peers.input(denied)));peers.allowed.set(false);worker.tick();await(store,14,"failed",worker,replica);assertEquals(2,calls.get());peers.allowed.set(true);
                var longest=peers.install(15,ds,"INSERT INTO business.sql_output SELECT 15,CURRENT_USER",86400,canonical);coordinator.submit(FixedExecutionOperation.SQL,bytes(peers.input(longest)));var claimed=store.claim(store.find(FixedExecutionOperation.SQL,15,115,3).orElseThrow().id(),"fixture-lifetime").orElseThrow();var fixedDeadline=claimed.deadline();assertEquals(86520,admin.queryForObject("SELECT extract(epoch from deadline_at-created_at)::bigint FROM metadata.execution_receipt WHERE id=?",Long.class,claimed.id()));assertTrue(store.renew(claimed));assertEquals(fixedDeadline,store.find(FixedExecutionOperation.SQL,15,115,3).orElseThrow().deadline());assertTrue(store.finish(claimed,"failed",null,"FIXTURE_RETIRED"));assertEquals(2,calls.get());
                assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.update("UPDATE metadata.execution_receipt SET status='queued' WHERE origin='workflow' AND run_id=13"));assertThrows(org.springframework.dao.DataAccessException.class,()->admin.update("DELETE FROM metadata.execution_receipt WHERE origin='workflow' AND run_id=13"));
                String stored=admin.queryForObject("SELECT string_agg(source_manifest_json::text||execution_json::text,'') FROM metadata.execution_receipt",String.class);for(String secret:List.of(password,runtimeSecret,readSecret,writeSecret,master))assertFalse(stored.contains(secret));
            }
        }
    }
    static byte[] status(long run){var n=new LinkedHashMap<String,Object>();n.put("receiptId",null);n.put("runId",run);n.put("attemptId",run+100);n.put("fence",3);n.put("operation",FixedExecutionOperation.SQL.operation());return bytes(n);}
    static void await(FixedReceiptStore store,long run,String state,FixedTransferWorker... workers)throws Exception{until(()->state.equals(store.find(FixedExecutionOperation.SQL,run,run+100,3).orElseThrow().state()));until(()->Arrays.stream(workers).mapToInt(FixedTransferWorker::physicalActive).sum()==0);}
}
