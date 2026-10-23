package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FixedQualityDurabilityPostgresIT {
    static void until(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertTrue(condition.getAsBoolean());}
    static String random(){byte[] b=new byte[24];new java.security.SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    @Test void ordinaryRuntimeAndReadOnlyBusinessCredentialsEnforceQualityReceipts()throws Exception{
        String url=System.getenv("AETHERIS_TEST_FIXED_QUALITY_URL"),password=System.getenv("AETHERIS_TEST_FIXED_QUALITY_PASSWORD");assertNotNull(url);var endpoint=URI.create(url.substring(5));assertEquals("127.0.0.1",endpoint.getHost());assertTrue(endpoint.getPort()>0);assertEquals("/aetheris_fixed_quality_audit",endpoint.getPath());assertNotNull(password);assertTrue(password.matches("[A-Za-z0-9_-]{32,}"));
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"audit_admin",password));var flyway=Flyway.configure().dataSource(url,"audit_admin",password).schemas("metadata").defaultSchema("metadata").locations("classpath:db/migration").load();assertTrue(flyway.migrate().migrationsExecuted>0);assertTrue(flyway.validateWithResult().validationSuccessful);assertEquals(0,flyway.info().pending().length);
        String runtimeSecret=random(),readSecret=random();admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");admin.execute("CREATE ROLE quality_runtime LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+runtimeSecret+"'");admin.execute("CREATE ROLE quality_reader LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+readSecret+"'");
        admin.execute("GRANT USAGE ON SCHEMA metadata TO quality_runtime; GRANT SELECT,INSERT,UPDATE ON metadata.execution_receipt TO quality_runtime; GRANT USAGE,SELECT ON SEQUENCE metadata.execution_receipt_id_seq TO quality_runtime; GRANT SELECT ON metadata.data_source TO quality_runtime");
        admin.execute("CREATE SCHEMA business; CREATE TABLE business.input(id INT,amount INT); INSERT INTO business.input VALUES(1,NULL),(1,2),(2,3)");
        admin.execute("CREATE VIEW business.slow AS SELECT i.id FROM business.input i CROSS JOIN (SELECT pg_sleep(2)) pause");admin.execute("GRANT USAGE ON SCHEMA business TO quality_reader; GRANT SELECT ON business.input,business.slow TO quality_reader");
        var runtime=new JdbcTemplate(new DriverManagerDataSource(url,"quality_runtime",runtimeSecret));var reader=new JdbcTemplate(new DriverManagerDataSource(url,"quality_reader",readSecret));assertEquals("quality_runtime",runtime.queryForObject("SELECT current_user",String.class));assertFalse(runtime.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->reader.queryForObject("SELECT count(*) FROM metadata.data_source",Long.class));assertThrows(org.springframework.dao.DataAccessException.class,()->reader.update("INSERT INTO business.input VALUES(9,9)"));
        String master=Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));var secrets=new DataSourceSecretService(master);
        long ds=admin.queryForObject("INSERT INTO metadata.data_source(name,type,host,port,database_name,username,password,status,managed) VALUES('read-only-quality','postgresql','127.0.0.1',?,'aetheris_fixed_quality_audit','quality_reader',?,'active',false) RETURNING id",Long.class,endpoint.getPort(),secrets.encrypt(readSecret));
        var sources=mock(DataSourceService.class);when(sources.getById(any())).thenAnswer(call->runtime.queryForObject("SELECT * FROM metadata.data_source WHERE id=?",(r,i)->{
            var d=new DataSource();d.setId(r.getLong("id"));d.setType(r.getString("type"));d.setHost(r.getString("host"));d.setPort(r.getInt("port"));d.setDatabaseName(r.getString("database_name"));d.setUsername(r.getString("username"));d.setPassword(r.getString("password"));d.setStatus(r.getString("status"));d.setManaged(r.getBoolean("managed"));d.setConnectionRevision(r.getLong("connection_revision"));return d;
        },(Object)call.getArgument(0)));
        var calls=new AtomicInteger();var slowEntered=new CountDownLatch(1);JdbcService jdbc=new JdbcService(secrets,false,new OutboundTargetPolicy()){
            @Override public Map<String,Object> query(DataSource source,String sql){calls.incrementAndGet();if(sql.contains("business.slow"))slowEntered.countDown();return super.query(source,sql);}
        };
        var store=new FixedReceiptStore(runtime);var execution=new FixedQualityExecution(sources,jdbc);
        try(var peers=new FixedQualityTestPeers()){
            var directory=new FixedExecutionAdapters(mock(FixedTransferPeers.class),mock(FixedTransferExecution.class),peers.peers,execution);var coordinator=new FixedTransferCoordinator(store,directory,Duration.ofMinutes(30));
            try(var worker=new FixedTransferWorker(store,directory,1);var replica=new FixedTransferWorker(new FixedReceiptStore(runtime),directory,1)){
                java.util.function.UnaryOperator<String> canonical=s->admin.queryForObject("SELECT ?::jsonb::text",String.class,s);
                var m=peers.install(11,ds,"sql","SELECT COUNT(*) FROM business.input",null,canonical);var input=input(m);byte[] submit=bytes(input);
                try(var threads=Executors.newFixedThreadPool(2)){
                    var a=threads.submit(()->coordinator.submit(FixedExecutionOperation.QUALITY,submit));var b=threads.submit(()->coordinator.submit(FixedExecutionOperation.QUALITY,submit));assertEquals(a.get(10,TimeUnit.SECONDS).receiptId(),b.get(10,TimeUnit.SECONDS).receiptId());
                    var first=threads.submit(()->{worker.tick();return true;});var second=threads.submit(()->{replica.tick();return true;});first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
                }
                await(store,11,"succeeded",worker,replica);assertEquals(1,calls.get());assertEquals(3,count(coordinator,11));
                peers.allowed.set(false);peers.sourceActive.set(false);assertEquals("succeeded",coordinator.submit(FixedExecutionOperation.QUALITY,submit).state());assertEquals(3,count(coordinator,11));assertEquals(1,calls.get());
                assertThrows(FixedReceiptStore.Conflict.class,()->coordinator.submit(FixedExecutionOperation.QUALITY,bytes(new FixedExecutionAdapter.Submission(11,0,1,2,"a".repeat(64)))));
                assertThrows(FixedTransferCoordinator.NotFound.class,()->coordinator.status(FixedExecutionOperation.TRANSFER,status(11,FixedExecutionOperation.TRANSFER)));peers.allowed.set(true);peers.sourceActive.set(true);
                for(var spec:List.of(new Spec(12,"not_null","amount IS NOT NULL",1),new Spec(13,"sql","SELECT COUNT(*) FROM business.input WHERE id<0",0),new Spec(14,"duplicate","id",1))){
                    var fixed=peers.install(spec.run,ds,spec.type,spec.expression,0,canonical);coordinator.submit(FixedExecutionOperation.QUALITY,bytes(input(fixed)));worker.tick();await(store,spec.run,"succeeded",worker,replica);assertEquals(spec.count,count(coordinator,spec.run));
                }
                long run=20;
                for(String expression:List.of("SELECT id AS violation_count FROM business.input","SELECT CAST(NULL AS BIGINT) AS violation_count","SELECT 1.5 AS violation_count","SELECT '3' AS violation_count","SELECT -1 AS violation_count","SELECT 9223372036854775808 AS violation_count","SELECT 1 AS violation_count,2 AS extra","SELECT id AS violation_count FROM business.input WHERE id<0")){
                    var fixed=peers.install(run,ds,"sql",expression,0,canonical);coordinator.submit(FixedExecutionOperation.QUALITY,bytes(input(fixed)));worker.tick();await(store,run,"unknown",worker,replica);assertNull(coordinator.status(FixedExecutionOperation.QUALITY,status(run,FixedExecutionOperation.QUALITY)).result());run++;
                }
                int before=calls.get();var revoked=peers.install(30,ds,"sql","SELECT COUNT(*) FROM business.input",0,canonical);coordinator.submit(FixedExecutionOperation.QUALITY,bytes(input(revoked)));peers.allowed.set(false);worker.tick();await(store,30,"failed",worker,replica);assertEquals(before,calls.get());peers.allowed.set(true);
                var revision=peers.install(31,ds,"sql","SELECT COUNT(*) FROM business.input",0,canonical);coordinator.submit(FixedExecutionOperation.QUALITY,bytes(input(revision)));admin.update("UPDATE metadata.data_source SET host='localhost' WHERE id=?",ds);worker.tick();await(store,31,"failed",worker,replica);assertEquals(before,calls.get());admin.update("UPDATE metadata.data_source SET host='127.0.0.1' WHERE id=?",ds);
                var timed=peers.install(32,ds,"sql","SELECT COUNT(*) FROM business.slow",0,canonical);var invocation=directory.get(FixedExecutionOperation.QUALITY).resolveAndAuthorize(input(timed));var plan=directory.get(FixedExecutionOperation.QUALITY).prepare(invocation);store.insert(invocation,plan.normalized(),"b".repeat(64),Duration.ofSeconds(1));
                worker.tick();assertTrue(slowEntered.await(3,TimeUnit.SECONDS));Thread.sleep(1100);store.expire();worker.heartbeat();await(store,32,"unknown",worker,replica);assertEquals(before+1,calls.get());assertEquals("unknown",coordinator.submit(FixedExecutionOperation.QUALITY,bytes(input(timed))).state());
                var queued=peers.install(33,ds,"sql","SELECT COUNT(*) FROM business.input",0,canonical);coordinator.submit(FixedExecutionOperation.QUALITY,bytes(input(queued)));
                try(var restarted=new FixedTransferWorker(store,directory,1)){restarted.tick();await(store,33,"succeeded",restarted);assertEquals(before+2,calls.get());}
                replica.tick();assertEquals(before+2,calls.get());assertEquals(3,reader.queryForObject("SELECT count(*) FROM business.input",Integer.class));
                assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.update("UPDATE metadata.execution_receipt SET status='queued' WHERE origin='quality' AND run_id=32"));
                assertThrows(org.springframework.dao.DataAccessException.class,()->admin.update("DELETE FROM metadata.execution_receipt WHERE origin='quality' AND run_id=32"));
                String stored=admin.queryForObject("SELECT string_agg(source_manifest_json::text||execution_json::text,'') FROM metadata.execution_receipt",String.class);for(String secret:List.of(password,runtimeSecret,readSecret,master))assertFalse(stored.contains(secret));
            }
        }
    }
    record Spec(long run,String type,String expression,long count){ }
    static FixedExecutionAdapter.Submission input(FixedQualityManifest m){return new FixedExecutionAdapter.Submission(m.binding().runId(),0,1,2,m.sha256());}
    static byte[] status(long run,FixedExecutionOperation op){var n=new LinkedHashMap<String,Object>();n.put("receiptId",null);n.put("runId",run);n.put("attemptId",0);n.put("fence",1);n.put("operation",op.operation());return bytes(n);}
    static long count(FixedTransferCoordinator coordinator,long run){return ((FixedQualityResult)coordinator.status(FixedExecutionOperation.QUALITY,status(run,FixedExecutionOperation.QUALITY)).result()).violationCount();}
    static void await(FixedReceiptStore store,long run,String state,FixedTransferWorker...workers)throws Exception{until(()->state.equals(store.find(FixedExecutionOperation.QUALITY,run,0,1).orElseThrow().state()));until(()->Arrays.stream(workers).mapToInt(FixedTransferWorker::physicalActive).sum()==0);}
}
