package com.aetheris.metadata.service;

import com.aetheris.metadata.security.MetadataProofFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only the reviewed disposable runner supplies these credentials; no skip or installed-database fallback. */
class QueryAuditPostgresIT {
    record Fixture(JdbcTemplate admin,JdbcTemplate runtime,String role) { }
    @AfterEach void reset() { RequestContextHolder.resetRequestAttributes(); }

    private Fixture fixture() { return fixture(false); }
    private Fixture fixture(boolean legacyUpgrade) {
        String url=System.getenv("AETHERIS_QUERY_AUDIT_URL"),password=System.getenv("AETHERIS_QUERY_AUDIT_PASSWORD");
        assertNotNull(url);assertNotNull(password);assertTrue(password.matches("[A-Za-z0-9_-]{32,}"));
        var endpoint=URI.create(url.substring(5));
        assertEquals("127.0.0.1",endpoint.getHost());assertTrue(endpoint.getPort()>0);
        assertEquals("/aetheris_query_audit",endpoint.getPath());assertNull(endpoint.getQuery());assertNull(endpoint.getUserInfo());
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"audit_admin",password));
        assertEquals("aetheris_query_audit",admin.queryForObject("SELECT current_database()",String.class));
        if(legacyUpgrade) {
            // Only this explicitly isolated disposable database is admitted above.
            admin.execute("DROP SCHEMA IF EXISTS metadata CASCADE");
            Flyway.configure().dataSource(url,"audit_admin",password).schemas("metadata").defaultSchema("metadata")
                    .locations("classpath:db/migration").target("31").load().migrate();
            admin.update("INSERT INTO metadata.adhoc_query_history(username,datasource_id,sql_text,status,error_message) VALUES ('alice',7,'SELECT legacy_private','failed','password=legacy-canary')");
            admin.update("INSERT INTO metadata.semantic_query_audit(query_kind,username,status) VALUES ('adhoc','alice','success')");
            admin.update("INSERT INTO metadata.semantic_query_audit(query_kind,username,status,api_key_id,admission_id,request_id,owner_uid) VALUES ('published_api','alice','success',1,?::uuid,'legacy-published',42)",UUID.randomUUID().toString());
        }
        var flyway=Flyway.configure().dataSource(url,"audit_admin",password).schemas("metadata").defaultSchema("metadata")
                .locations("classpath:db/migration").load();
        var result=flyway.migrate();assertTrue(result.migrationsExecuted==0||result.migrationsExecuted==1||result.migrationsExecuted==32);
        // Flyway also records schema creation; only versioned SQL entries count as source migrations.
        assertEquals(32L,Arrays.stream(flyway.info().applied()).filter(migration->migration.getVersion()!=null).count());
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(0,flyway.info().pending().length);assertEquals(0,flyway.migrate().migrationsExecuted);
        if(!legacyUpgrade) admin.update("DELETE FROM metadata.semantic_query_audit");
        String role="audit_runtime_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(new java.security.SecureRandom().generateSeed(32));
        admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        admin.execute("CREATE ROLE "+role+" LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+secret+"'");
        admin.execute("GRANT USAGE ON SCHEMA metadata TO "+role);
        admin.execute("GRANT SELECT,INSERT ON metadata.semantic_query_audit TO "+role);
        admin.execute("GRANT SELECT,INSERT,DELETE ON metadata.adhoc_query_history TO "+role);
        admin.execute("GRANT USAGE,SELECT ON SEQUENCE metadata.semantic_query_audit_id_seq TO "+role);
        admin.execute("GRANT USAGE,SELECT ON SEQUENCE metadata.adhoc_query_history_id_seq TO "+role);
        var runtime=new JdbcTemplate(new DriverManagerDataSource(url,role,secret));
        assertEquals(role,runtime.queryForObject("SELECT current_user",String.class));
        assertFalse(runtime.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.queryForObject("SELECT count(*) FROM metadata.data_source",Long.class));
        return new Fixture(admin,runtime,role);
    }

    private void asVerified(String username,Runnable action) throws Exception {
        asVerified(username.equals("alice")?42:43,username,action);
    }

    private void asVerified(long uid,String username,Runnable action) throws Exception {
        var proof=new MetadataProofFixture();var request=new MockHttpServletRequest("POST","/api/metadata/query");
        request.addHeader(com.aetheris.security.protocol.transport.SignedHttpClient.USER_PROOF,
                proof.user("metadata",uid,username,3));
        request.addHeader("X-Request-Id","forged-correlation-id");
        var response=new MockHttpServletResponse();
        try {
            proof.direct((checkedUid,name,av)->true).doFilter(request,response,(req,res)->{
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes((jakarta.servlet.http.HttpServletRequest)req));
                action.run();
            });
            assertEquals(200,response.getStatus());
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }

    @Test void actualStoredProvenanceRedactionAndPercentilesRespectTheRequestedScope() throws Exception {
        var f=fixture();var service=new QueryAuditService(f.runtime());
        asVerified("alice",()->service.record("adhoc",null,null,11L,"alice","success",2L,10L,false,null,null,"SELECT 'audit-canary'"));
        asVerified("bob",()->service.record("table",null,7L,11L,"bob","failed",null,5000L,false,
                "not-a-safe-code","password=audit-canary; jdbc:postgresql://private/secret SELECT 'audit-canary'","SELECT 'audit-canary'"));
        asVerified("alice",()->service.record("adhoc",null,null,11L,"alice","failed",null,5000L,false,"TIMEOUT","audit-canary",null));
        f.admin().update("UPDATE metadata.semantic_query_audit SET created_at=clock_timestamp()-interval '91 days' WHERE username='alice' AND status='failed'");
        var stored=f.runtime().queryForList("SELECT username,error_code,error_message,query_hash,request_id FROM metadata.semantic_query_audit ORDER BY id");
        assertEquals(3,stored.size());assertEquals("metadata-v2-test",stored.getFirst().get("request_id"));
        assertEquals("metadata-v2-test",stored.get(1).get("request_id"));assertEquals("metadata-v2-test",stored.getLast().get("request_id"));
        assertEquals("QUERY_FAILED",stored.get(1).get("error_code"));
        assertEquals("Query failed; correlate with requestId",stored.get(1).get("error_message"));
        assertTrue(((String)stored.getFirst().get("query_hash")).matches("[a-f0-9]{64}"));
        assertFalse(stored.toString().contains("audit-canary"));assertFalse(stored.toString().contains("forged-correlation-id"));
        var own=service.summary(42L,false,30,null);
        var global=service.summary(42L,true,30,null);
        assertEquals(1L,own.get("total"));assertEquals(1L,own.get("success"));assertEquals(0L,own.get("failed"));
        assertEquals(2L,global.get("total"));assertEquals(1L,global.get("failed"));assertEquals(0.5,(double)global.get("successRate"));
        assertEquals(2505.0,(double)global.get("p50DurationMs"),0.001);
        assertEquals(4750.5,(double)global.get("p95DurationMs"),0.001);assertEquals(1L,global.get("slowQueries"));
        var bounded=service.summary(42L,false,1000,1L);assertEquals(90,bounded.get("days"));assertEquals(100L,bounded.get("slowThresholdMs"));
        assertEquals(0L,service.summary(Long.MAX_VALUE,false,30,null).get("total"));
    }

    @Test void lostInsertPrivilegeAndInvalidKeyProvenanceCannotBeCountedAsPersistedQueries() throws Exception {
        var f=fixture();var metrics=new SimpleMeterRegistry();var service=new QueryAuditService(f.runtime(),metrics);
        f.admin().execute("REVOKE INSERT ON metadata.semantic_query_audit FROM "+f.role());
        asVerified("alice",()->assertThrows(org.springframework.dao.DataAccessException.class,
                ()->service.record("adhoc",null,null,11L,"alice","success",1L,10L,false,null,null,"select 1")));
        assertEquals(1.0,metrics.get("aetheris.query.audit.persist.failed").counter().count());
        assertEquals(0.0,metrics.get("aetheris.query.audit.count").counter().count());
        f.admin().execute("GRANT INSERT ON metadata.semantic_query_audit TO "+f.role());
        asVerified("alice",()->service.record("adhoc",null,null,11L,"alice","success",1L,10L,false,null,null,"select 1"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                ()->service.recordPublished("published_api","ASSET",1L,11L,"alice","success",1L,10L,"select 1",1L,UUID.randomUUID().toString(),"invalid request id",42L));
        assertEquals(2.0,metrics.get("aetheris.query.audit.persist.failed").counter().count());
        assertEquals(1.0,metrics.get("aetheris.query.audit.count").counter().count());
        assertEquals(1L,f.runtime().queryForObject("SELECT count(*) FROM metadata.semantic_query_audit",Long.class));
        assertEquals(0L,f.runtime().queryForObject("SELECT count(*) FROM metadata.semantic_query_audit WHERE api_key_id IS NOT NULL",Long.class));
    }

    @Test void recreatedSameNameAccountDoesNotInheritPersonalAudit() throws Exception {
        var f=fixture();var service=new QueryAuditService(f.runtime());
        asVerified(42,"alice",()->service.record("adhoc",null,null,11L,"alice","success",1L,10L,false,null,null,"select 1"));
        asVerified(43,"alice",()->assertEquals(0L,service.summary(43L,false,30,null).get("total"),
                "New UID must not inherit the deleted same-name user's audit activity"));
        assertEquals(1L,f.admin().queryForObject("SELECT count(*) FROM metadata.semantic_query_audit",Long.class));
    }

    @Test void realV31UpgradePreservesLegacyIsolationAndRuntimeHistoryOwnership() throws Exception {
        var f=fixture(true);var service=new QueryAuditService(f.runtime());
        assertEquals(1L,f.admin().queryForObject("SELECT count(*) FROM metadata.semantic_query_audit WHERE actor_uid IS NULL",Long.class));
        assertEquals(42L,f.admin().queryForObject("SELECT actor_uid FROM metadata.semantic_query_audit WHERE api_key_id=1",Long.class));
        assertEquals(0L,service.summary(43L,false,30,null).get("total"));
        assertEquals(1L,service.summary(42L,false,30,null).get("total"));
        assertEquals(2L,service.summary(42L,true,30,null).get("total"));
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(com.aetheris.metadata.mapper.QueryHistoryMapper.class);
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(Objects.requireNonNull(f.runtime().getDataSource()));factory.setConfiguration(configuration);factory.afterPropertiesSet();
        try(var session=Objects.requireNonNull(factory.getObject()).openSession(true)) {
            var history=new QueryHistoryService(session.getMapper(com.aetheris.metadata.mapper.QueryHistoryMapper.class));
            assertTrue(history.list(42L,null).isEmpty());
            long legacyId=f.admin().queryForObject("SELECT id FROM metadata.adhoc_query_history WHERE owner_uid IS NULL",Long.class);
            assertFalse(history.delete(42L,legacyId)); assertEquals(0,history.clear(42L).deleted());
            assertThrows(org.springframework.dao.DataAccessException.class,()->f.admin().update("UPDATE metadata.adhoc_query_history SET owner_uid=42 WHERE id=?",legacyId));
            assertThrows(org.springframework.dao.DataAccessException.class,()->f.runtime().update("INSERT INTO metadata.adhoc_query_history(username,datasource_id,sql_text,status) VALUES ('alice',7,'select 1','success')"));
            assertThrows(org.springframework.dao.DataAccessException.class,()->f.runtime().update("INSERT INTO metadata.semantic_query_audit(query_kind,username,status) VALUES ('adhoc','alice','success')"));
            var own=new com.aetheris.metadata.entity.QueryHistory();own.setOwnerUid(42L);own.setUsername("alice");
            own.setDatasourceId(7L);own.setSqlText("SELECT 'own-private-sql'");own.setStatus("failed");
            own.setErrorMessage("password=driver-canary");own.setRequestId("metadata-v2-test");history.record(own);
            assertEquals("SELECT 'own-private-sql'",history.list(42L,null).getFirst().sqlText());
            assertFalse(history.list(42L,null).toString().contains("driver-canary"));assertTrue(history.list(43L,null).isEmpty());
            assertFalse(history.delete(43L,own.getId()));assertEquals(0,history.clear(43L).deleted());
            assertThrows(org.springframework.dao.DataAccessException.class,()->f.admin().update("UPDATE metadata.adhoc_query_history SET owner_uid=43 WHERE id=?",own.getId()));
            assertThrows(org.springframework.dao.DataAccessException.class,()->f.admin().update("UPDATE metadata.semantic_query_audit SET actor_uid=43 WHERE api_key_id=1"));
            f.admin().update("INSERT INTO metadata.adhoc_query_history(username,datasource_id,sql_text,status,owner_uid) SELECT 'alice',7,'select 1','success',42 FROM generate_series(1,500)");
            var first=history.clear(42L);assertEquals(500,first.deleted());assertTrue(first.hasMore());
            var second=history.clear(42L);assertEquals(1,second.deleted());assertFalse(second.hasMore());
            assertEquals(1L,f.admin().queryForObject("SELECT count(*) FROM metadata.adhoc_query_history WHERE owner_uid IS NULL",Long.class));
        }
    }
}
