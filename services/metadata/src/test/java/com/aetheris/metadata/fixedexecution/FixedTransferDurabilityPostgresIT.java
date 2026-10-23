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
import java.util.function.BooleanSupplier;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real PG receipts + ordinary runtime + business JDBC credentials; only peer HTTP is replaced. */
class FixedTransferDurabilityPostgresIT {
    static String random(){byte[] n=new byte[24];new java.security.SecureRandom().nextBytes(n);return Base64.getUrlEncoder().withoutPadding().encodeToString(n);}
    static void until(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(10);assertTrue(condition.getAsBoolean());}
    @Test void realRuntimeAndBusinessJdbcNeverReplayAmbiguousTransfers()throws Exception{
        String url=System.getenv("AETHERIS_TEST_FIXED_TRANSFER_URL"),password=System.getenv("AETHERIS_TEST_FIXED_TRANSFER_PASSWORD");
        assertNotNull(url);var endpoint=URI.create(url.substring(5));assertEquals("127.0.0.1",endpoint.getHost());assertTrue(endpoint.getPort()>0);assertEquals("/aetheris_fixed_transfer_audit",endpoint.getPath());assertNotNull(password);assertTrue(password.matches("[A-Za-z0-9_-]{32,}"));
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"audit_admin",password));
        var flyway=Flyway.configure().dataSource(url,"audit_admin",password).schemas("metadata").defaultSchema("metadata").locations("classpath:db/migration").load();assertTrue(flyway.migrate().migrationsExecuted>0);assertTrue(flyway.validateWithResult().validationSuccessful);assertEquals(0,flyway.info().pending().length);
        String runtimePassword=random(),readPassword=random(),writePassword=random();
        admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        admin.execute("CREATE ROLE metadata_runtime LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+runtimePassword+"'");
        admin.execute("CREATE ROLE transfer_reader LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+readPassword+"'");
        admin.execute("CREATE ROLE transfer_writer LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+writePassword+"'");
        admin.execute("GRANT USAGE ON SCHEMA metadata TO metadata_runtime");admin.execute("GRANT SELECT,INSERT,UPDATE ON metadata.execution_receipt TO metadata_runtime");admin.execute("GRANT USAGE,SELECT ON SEQUENCE metadata.execution_receipt_id_seq TO metadata_runtime");admin.execute("GRANT SELECT ON metadata.data_source TO metadata_runtime");
        admin.execute("CREATE SCHEMA business; CREATE TABLE business.input(id INT PRIMARY KEY); INSERT INTO business.input VALUES(1),(2)");
        admin.execute("CREATE TABLE business.output(id INT,read_actor TEXT,write_actor TEXT DEFAULT CURRENT_USER)");
        admin.execute("GRANT USAGE ON SCHEMA business TO transfer_reader,transfer_writer; GRANT SELECT ON business.input,business.output TO transfer_reader; GRANT SELECT,INSERT,DELETE ON business.output TO transfer_writer");
        var runtime=new JdbcTemplate(new DriverManagerDataSource(url,"metadata_runtime",runtimePassword));
        var reader=new JdbcTemplate(new DriverManagerDataSource(url,"transfer_reader",readPassword));
        assertEquals("metadata_runtime",runtime.queryForObject("SELECT current_user",String.class));
        assertFalse(runtime.queryForObject("SELECT rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user",Boolean.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->reader.queryForObject("SELECT count(*) FROM metadata.data_source",Long.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->reader.update("INSERT INTO business.output(id) VALUES(999)"));
        String master=Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));var secrets=new DataSourceSecretService(master);
        long source=insertSource(admin,endpoint.getPort(),secrets.encrypt(readPassword),null,null,"source");
        long target=insertSource(admin,endpoint.getPort(),secrets.encrypt(readPassword),"transfer_writer",secrets.encrypt(writePassword),"target");
        var sources=mock(DataSourceService.class);
        when(sources.getById(any())).thenAnswer(call->runtime.queryForObject("SELECT * FROM metadata.data_source WHERE id=?",(r,i)->{
            var d=new DataSource();d.setId(r.getLong("id"));d.setName(r.getString("name"));d.setType(r.getString("type"));d.setHost(r.getString("host"));d.setPort(r.getInt("port"));d.setDatabaseName(r.getString("database_name"));d.setUsername(r.getString("username"));d.setPassword(r.getString("password"));d.setWriteUsername(r.getString("write_username"));d.setWritePassword(r.getString("write_password"));d.setManaged(r.getBoolean("managed"));d.setStatus(r.getString("status"));d.setConnectionRevision(r.getLong("connection_revision"));return d;
        },(Object)call.getArgument(0)));
        AtomicInteger dispatches=new AtomicInteger();AtomicReference<String> mode=new AtomicReference<>("");
        JdbcService jdbc=new JdbcService(secrets,false,new OutboundTargetPolicy()){
            @Override public Map<String,Object> transfer(DataSource s,DataSource t,String sql,String table,String writeMode,Integer batch){
                dispatches.incrementAndGet();var result=super.transfer(s,t,sql,table,writeMode,batch);
                if(mode.get().equals("drop-result"))throw new IllegalStateException("Fixture loses response after actual target commit");
                if(mode.get().equals("deny-completion"))admin.execute("REVOKE UPDATE ON metadata.execution_receipt FROM metadata_runtime");return result;
            }
        };
        var store=new FixedReceiptStore(runtime);var replica=new FixedReceiptStore(new JdbcTemplate(new DriverManagerDataSource(url,"metadata_runtime",runtimePassword)));
        var execution=new FixedTransferExecution(sources,jdbc);
        try(var peers=new FixedTransferTestPeers();var worker=new FixedTransferWorker(store,peers.peers,execution,1);var worker2=new FixedTransferWorker(replica,peers.peers,execution,1)){
            var coordinator=new FixedTransferCoordinator(store,peers.peers,execution,Duration.ofMinutes(30));
            java.util.function.LongFunction<FixedTransferManifest> manifest=run->peers.install(run,source,target,"SELECT id,current_user AS read_actor FROM business.input","business.output",s->admin.queryForObject("SELECT ?::jsonb::text",String.class,s));
            var m=manifest.apply(11);byte[] submit=bytes(new FixedTransferManifest.Submit(11,0,1,2,m.sha256()));
            FixedReceiptStore.Receipt first;
            try(var threads=Executors.newFixedThreadPool(2)){
                var a=threads.submit(()->coordinator.submit(submit));var b=threads.submit(()->coordinator.submit(submit));first=a.get(10,TimeUnit.SECONDS);assertEquals(first.receiptId(),b.get(10,TimeUnit.SECONDS).receiptId());
                var start=new CountDownLatch(1);var x=threads.submit(()->{start.await();worker.tick();return true;});var y=threads.submit(()->{start.await();worker2.tick();return true;});start.countDown();x.get(5,TimeUnit.SECONDS);y.get(5,TimeUnit.SECONDS);
            }
            until(()->"succeeded".equals(store.find(11,0,1).orElseThrow().state()));until(()->worker.physicalActive()+worker2.physicalActive()==0);
            assertEquals(1,dispatches.get());assertEquals(2,reader.queryForObject("SELECT count(*) FROM business.output",Integer.class));
            assertEquals(List.of("transfer_reader:transfer_writer"),admin.queryForList("SELECT DISTINCT read_actor||':'||write_actor FROM business.output",String.class));
            var receipt=coordinator.status(status(11,null));assertEquals(2,((FixedReceiptStore.Result)receipt.result()).rowsWritten());
            int lookups=peers.lookups.get();peers.allowed.set(false);peers.sourceActive.set(false);
            byte[] reordered=("{\"manifestSha256\":\""+m.sha256()+"\",\"executionRevision\":2,\"fence\":1,\"attemptId\":0,\"runId\":11}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(first.receiptId(),coordinator.submit(reordered).receiptId());assertEquals(lookups,peers.lookups.get());assertEquals("succeeded",coordinator.status(status(11,null)).state());
            assertThrows(FixedReceiptStore.Conflict.class,()->coordinator.submit(bytes(new FixedTransferManifest.Submit(11,0,1,2,"a".repeat(64)))));assertEquals(1,dispatches.get());
            assertThrows(FixedReceiptStore.Conflict.class,()->coordinator.status(status(11,first.receiptId()+1)));
            peers.sourceActive.set(true);peers.allowed.set(true);
            var denied=manifest.apply(12);coordinator.submit(bytes(new FixedTransferManifest.Submit(12,0,1,2,denied.sha256())));peers.allowed.set(false);worker.tick();until(()->"failed".equals(store.find(12,0,1).orElseThrow().state()));until(()->worker.physicalActive()==0);assertEquals(1,dispatches.get());
            peers.allowed.set(true);var changed=manifest.apply(13);coordinator.submit(bytes(new FixedTransferManifest.Submit(13,0,1,2,changed.sha256())));
            admin.update("UPDATE metadata.data_source SET host='localhost' WHERE id=?",target);worker.tick();until(()->"failed".equals(store.find(13,0,1).orElseThrow().state()));until(()->worker.physicalActive()==0);assertEquals(1,dispatches.get());admin.update("UPDATE metadata.data_source SET host='127.0.0.1' WHERE id=?",target);
            mode.set("drop-result");var lost=manifest.apply(14);byte[] lostSubmit=bytes(new FixedTransferManifest.Submit(14,0,1,2,lost.sha256()));coordinator.submit(lostSubmit);worker.tick();until(()->"unknown".equals(store.find(14,0,1).orElseThrow().state()));until(()->worker.physicalActive()==0);
            assertEquals(2,dispatches.get());assertEquals(4,reader.queryForObject("SELECT count(*) FROM business.output",Integer.class));assertEquals("unknown",coordinator.submit(lostSubmit).state());worker2.tick();assertEquals(2,dispatches.get());
            mode.set("deny-completion");var failWrite=manifest.apply(15);coordinator.submit(bytes(new FixedTransferManifest.Submit(15,0,1,2,failWrite.sha256())));worker.tick();until(()->worker.physicalActive()==0);
            assertEquals(3,dispatches.get());assertEquals("executing",store.find(15,0,1).orElseThrow().state());assertEquals(6,reader.queryForObject("SELECT count(*) FROM business.output",Integer.class));
            admin.execute("GRANT UPDATE ON metadata.execution_receipt TO metadata_runtime");admin.update("UPDATE metadata.execution_receipt SET worker_lease_expires_at=clock_timestamp()+INTERVAL '50 milliseconds' WHERE run_id=15");Thread.sleep(100);store.expire();assertEquals("unknown",store.find(15,0,1).orElseThrow().state());
            mode.set("");var expired=manifest.apply(16);coordinator.submit(bytes(new FixedTransferManifest.Submit(16,0,1,2,expired.sha256())));var claimed=store.claim(store.find(16,0,1).orElseThrow().id(),"gone-replica").orElseThrow();
            worker2.tick();assertEquals("executing",store.find(16,0,1).orElseThrow().state(),"A replica must leave another live worker alone");
            admin.update("UPDATE metadata.execution_receipt SET worker_lease_expires_at=clock_timestamp()+INTERVAL '50 milliseconds' WHERE id=?",claimed.id());Thread.sleep(100);store.expire();assertEquals("unknown",store.find(16,0,1).orElseThrow().state());assertFalse(store.finish(claimed,"succeeded",new FixedReceiptStore.Result(1,1,1),null));
            try(var restarted=new FixedTransferWorker(new FixedReceiptStore(runtime),peers.peers,execution,1)){restarted.tick();assertEquals(0,restarted.physicalActive());assertEquals(3,dispatches.get());}
            var queue=manifest.apply(17);var p=execution.prepare(queue);store.insert(queue,p.normalized(),"b".repeat(64),Duration.ofMillis(50));Thread.sleep(100);store.expire();assertEquals("failed",store.find(17,0,1).orElseThrow().state());assertEquals(3,dispatches.get());
            var durable=manifest.apply(18);coordinator.submit(bytes(new FixedTransferManifest.Submit(18,0,1,2,durable.sha256())));
            try(var restarted=new FixedTransferWorker(new FixedReceiptStore(runtime),peers.peers,execution,1)){
                restarted.tick();until(()->"succeeded".equals(store.find(18,0,1).orElseThrow().state()));until(()->restarted.physicalActive()==0);
                assertEquals(4,dispatches.get());assertEquals(8,reader.queryForObject("SELECT count(*) FROM business.output",Integer.class));
            }
            assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.update("DELETE FROM metadata.execution_receipt WHERE run_id=14"));
            assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.update("UPDATE metadata.execution_receipt SET status='queued' WHERE run_id=14"));
            String persisted=admin.queryForObject("SELECT string_agg(source_manifest_json::text||execution_json::text,'') FROM metadata.execution_receipt",String.class);for(String secret:List.of(password,runtimePassword,readPassword,writePassword,master))assertFalse(persisted.contains(secret));
        }
    }
    static long insertSource(JdbcTemplate admin,int port,String read,String writer,String write,String name){return admin.queryForObject("INSERT INTO metadata.data_source(name,type,host,port,database_name,username,password,write_username,write_password,status,managed) VALUES(?,'postgresql','127.0.0.1',?,'aetheris_fixed_transfer_audit','transfer_reader',?,?,?,'active',false) RETURNING id",Long.class,name,port,read,writer,write);}
    static byte[] status(long run,Long receipt){var n=new LinkedHashMap<String,Object>();n.put("receiptId",receipt);n.put("runId",run);n.put("attemptId",0);n.put("fence",1);n.put("operation",FixedTransferManifest.OPERATION);return bytes(n);}
}
