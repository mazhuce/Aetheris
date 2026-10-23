package com.aetheris.security.servlet;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit disposable-PG entrypoint. This does not start Tomcat or any existing application. */
class PostgresNonceStoreIT {
    @Test void actualDatabaseClaimsAreAtomicDurableAndBoundedAcrossTwoReceivers() throws Exception {
        String url=System.getenv("AETHERIS_TEST_NONCE_POSTGRES_URL"),password=System.getenv("AETHERIS_TEST_NONCE_POSTGRES_PASSWORD");
        assertNotNull(url,"Use scripts/audit-nonce-store.py");assertNotNull(password);
        var uri=URI.create(url.substring("jdbc:".length()));assertEquals("127.0.0.1",uri.getHost());assertEquals("/aetheris_nonce_audit",uri.getPath());
        assertTrue(password.matches("[A-Za-z0-9_-]{24,}"));
        long now=Instant.now().getEpochSecond();Clock clock=Clock.fixed(Instant.ofEpochSecond(now),ZoneOffset.UTC);
        try(Connection admin=DriverManager.getConnection(url,"nonce_admin",password);Statement sql=admin.createStatement()) {
            sql.execute("CREATE SCHEMA metadata");
            String template;
            try(var input=getClass().getResourceAsStream("/internal-request-nonce.sql.template")){assertNotNull(input);template=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
            sql.execute(template.replace("SCHEMA","metadata"));
            sql.execute("CREATE ROLE nonce_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '"+password+"'");
            sql.execute("GRANT USAGE ON SCHEMA metadata TO nonce_runtime; GRANT SELECT, INSERT, DELETE ON metadata.internal_request_nonce TO nonce_runtime");
            var source=new TestDataSource(()->DriverManager.getConnection(url,"nonce_runtime",password));
            var first=new PostgresNonceStore(source,"metadata",clock);var second=new PostgresNonceStore(source,"metadata",clock);
            String nonce=Nonce.random();
            var winners=new AtomicInteger();var start=new CountDownLatch(1);
            try(var workers=Executors.newFixedThreadPool(16)) {
                var jobs=new ArrayList<Future<?>>();
                for(int i=0;i<64;i++){var store=i%2==0?first:second;jobs.add(workers.submit(()->{start.await();if(store.claim("workflow",nonce,now+30))winners.incrementAndGet();return null;}));}
                start.countDown();for(var job:jobs)job.get(15,TimeUnit.SECONDS);
            }
            assertEquals(1,winners.get());assertFalse(new PostgresNonceStore(source,"metadata",clock).claim("workflow",nonce,now+30));
            assertTrue(first.claim("quality",nonce,now+30));
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM metadata.internal_request_nonce")){rows.next();assertEquals(2,rows.getInt(1));}
            // The complete filter uses the real shared store: two receivers still execute a fresh nonce once.
            assertSingleExecutionThroughTwoFilters(first,second,clock,now);
            sql.execute("CREATE ROLE nonce_reader LOGIN PASSWORD '"+password+"'; GRANT USAGE ON SCHEMA metadata TO nonce_reader; GRANT SELECT ON metadata.internal_request_nonce TO nonce_reader");
            var denied=new PostgresNonceStore(new TestDataSource(()->DriverManager.getConnection(url,"nonce_reader",password)),"metadata",clock);
            assertEquals("42501",assertThrows(SQLException.class,()->denied.claim("workflow",Nonce.random(),now+30)).getSQLState());
            // Retention only removes rows expired more than a day ago, in bounded batches.
            try(var insert=admin.prepareStatement("INSERT INTO metadata.internal_request_nonce(caller,nonce,expires_at) VALUES ('workflow',?,?)")) {
                for(int i=0;i<5;i++){insert.setString(1,Nonce.random());insert.setLong(2,now-86401);insert.executeUpdate();}
                insert.setString(1,Nonce.random());insert.setLong(2,now-3600);insert.executeUpdate();
            }
            assertEquals(2,first.purgeExpired(2));assertEquals(2,second.purgeExpired(2));assertEquals(1,first.purgeExpired(2));assertEquals(0,second.purgeExpired(2));
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM metadata.internal_request_nonce")){rows.next();assertEquals(4,rows.getInt(1));}
            try(var insert=admin.prepareStatement("INSERT INTO metadata.internal_request_nonce(caller,nonce,expires_at) VALUES ('workflow',?,?)")) {
                for(int i=0;i<6;i++){insert.setString(1,Nonce.random());insert.setLong(2,now-86401);insert.executeUpdate();}
            }
            // Concurrent retention can select the same old rows. DELETE remains bounded and committed;
            // it must not require UPDATE privilege merely to acquire a selection lock.
            int removed;
            try(var workers=Executors.newFixedThreadPool(2)) {
                var a=workers.submit(()->first.purgeExpired(2));var b=workers.submit(()->second.purgeExpired(2));
                int left=a.get(5,TimeUnit.SECONDS),right=b.get(5,TimeUnit.SECONDS);
                assertTrue(left>=0&&left<=2);assertTrue(right>=0&&right<=2);removed=left+right;assertTrue(removed>=2&&removed<=4);
            }
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM metadata.internal_request_nonce")){rows.next();assertEquals(10-removed,rows.getInt(1));}
        }
    }
    private void assertSingleExecutionThroughTwoFilters(NonceStore first,NonceStore second,Clock clock,long now) throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");var w=generator.generateKeyPair();var m=generator.generateKeyPair();
        var registry=new KeyRegistry(List.of(new KeyRegistry.Entry("workflow-1",KeyPurpose.WORKLOAD,"workflow",w.getPublic()),new KeyRegistry.Entry("metadata-1",KeyPurpose.WORKLOAD,"metadata",m.getPublic())));
        var verifier=new ProtocolVerifier(registry,clock,TimePolicy.defaults());
        var workflow=new ProtocolSigner("workflow-1",KeyPurpose.WORKLOAD,"workflow",w.getPrivate(),registry,clock,TimePolicy.defaults());
        var metadata=new ProtocolSigner("metadata-1",KeyPurpose.WORKLOAD,"metadata",m.getPrivate(),registry,clock,TimePolicy.defaults());
        String nonce=Nonce.random(),path="/internal/v2/nonce-audit",operation="nonce.audit";byte[] body="{}".getBytes(StandardCharsets.UTF_8);
        String compact=workflow.sign(new WorkloadRequestProof(2,"workflow","metadata","POST",path,BodyHash.sha256(body),nonce,now,now+30,operation,BodyHash.userProof(null)));
        RoutePolicy routes=(method,target)->Optional.of(new RoutePolicy.Route(operation,Set.of("workflow"),RoutePolicy.UserMode.FORBIDDEN));
        var filters=List.of(new WorkloadProofFilter("metadata",verifier,metadata,routes,first,(uid,user,av)->false,clock,TimePolicy.defaults()),
                new WorkloadProofFilter("metadata",verifier,metadata,routes,second,(uid,user,av)->false,clock,TimePolicy.defaults()));
        var executions=new AtomicInteger();var accepted=new AtomicInteger();var conflicts=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(8)) {
            var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<24;i++){var filter=filters.get(i%2);jobs.add(workers.submit(()->{
                var request=new MockHttpServletRequest("POST",path);request.setContent(body);request.addHeader(SignedHttpClient.REQUEST_PROOF,compact);
                var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{executions.incrementAndGet();res.getWriter().write("{}");});
                verifier.verifyResponse(response.getHeader(SignedHttpClient.RESPONSE_PROOF),new ExpectedResponse("metadata","workflow",nonce,response.getStatus(),BodyHash.sha256(response.getContentAsByteArray())));
                if(response.getStatus()==200)accepted.incrementAndGet();else if(response.getStatus()==409)conflicts.incrementAndGet();else fail("Unexpected filter status "+response.getStatus());return null;
            }));}
            for(var job:jobs)job.get(15,TimeUnit.SECONDS);
        }
        assertEquals(1,executions.get());assertEquals(1,accepted.get());assertEquals(23,conflicts.get());
    }
}
