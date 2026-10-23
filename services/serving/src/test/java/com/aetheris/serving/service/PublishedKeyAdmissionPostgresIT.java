package com.aetheris.serving.service;

import com.aetheris.serving.dto.*;
import com.aetheris.serving.model.*;
import com.aetheris.serving.security.*;
import com.aetheris.security.protocol.BodyHash;
import com.aetheris.security.servlet.TrustedUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit isolated PG entry. Never discovers credentials or connects to the application's default database. */
class PublishedKeyAdmissionPostgresIT {
    static String random(){return Base64.getUrlEncoder().withoutPadding().encodeToString(new java.security.SecureRandom().generateSeed(32));}
    static <T> List<T> parallel(int count,Supplier<T> action)throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)){var jobs=new ArrayList<Future<T>>();for(int i=0;i<count;i++)jobs.add(pool.submit(action::get));var results=new ArrayList<T>();for(var job:jobs)results.add(job.get(15,TimeUnit.SECONDS));return results;}
    }
    static final Map<String,Object> ASSET=Map.of("id",11L,"assetCode","orders_api","version","v1","targetType","table","targetRef","13");
    static byte[] input(PublishedKeyAdmissionService.Admission a){return PublishedKeyJson.bytes(a.reference().map());}
    @Test void ordinaryRuntimeTransactionsKeepIdentityQuotaRotationAndSingleUseClaimsAtomic()throws Exception {
        String url=System.getenv("AETHERIS_TEST_PUBLISHED_KEY_URL"),password=System.getenv("AETHERIS_TEST_PUBLISHED_KEY_PASSWORD");assertNotNull(url);assertTrue(url.startsWith("jdbc:postgresql://"));URI endpoint=URI.create(url.substring(5));assertEquals("127.0.0.1",endpoint.getHost());assertTrue(endpoint.getPort()>0);assertEquals("/aetheris_key_audit",endpoint.getPath());assertNull(endpoint.getQuery());assertNotNull(password);assertTrue(password.matches("[A-Za-z0-9_-]{32,}"));
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,"audit_admin",password));
        var baseline=Flyway.configure().dataSource(url,"audit_admin",password).schemas("serving").defaultSchema("serving").locations("classpath:db/migration").target("10").load();assertEquals(10,baseline.migrate().migrationsExecuted);
        String legacySecret="ak_"+"a".repeat(12)+"_"+"a".repeat(52);
        long legacyKey=admin.queryForObject("INSERT INTO serving.service_api_key(key_prefix,key_hash,key_name,owner_username,created_by) VALUES(?,?,?,?,?) RETURNING id",Long.class,"a".repeat(12),BodyHash.sha256(legacySecret.getBytes(StandardCharsets.UTF_8)),"legacy","alice","old-admin");
        long legacyApp=admin.queryForObject("INSERT INTO serving.service_api_key_application(application_name,requester,justification) VALUES('legacy','alice','legacy') RETURNING id",Long.class);
        var migrated=Flyway.configure().dataSource(url,"audit_admin",password).schemas("serving").defaultSchema("serving").locations("classpath:db/migration").load();assertEquals(1,migrated.migrate().migrationsExecuted);assertTrue(migrated.validateWithResult().validationSuccessful);assertEquals(0,migrated.info().pending().length);
        String runtimePassword=random();admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");admin.execute("CREATE ROLE key_runtime LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+runtimePassword+"'");admin.execute("GRANT USAGE ON SCHEMA serving TO key_runtime; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA serving TO key_runtime; GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA serving TO key_runtime");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"key_runtime",runtimePassword));assertEquals("key_runtime",jdbc.queryForObject("SELECT current_user",String.class));assertFalse(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("CREATE TABLE public.forbidden(id INT)"));
        try(var f=new V2ServingFixture()) {
            var validator=new ApiKeyOwnerValidator(f.auth);var keys=new ApiKeyService(jdbc,validator);var applications=new ApiKeyApplicationService(jdbc,keys);var admissions=new PublishedKeyAdmissionService(jdbc,keys);var replica=new PublishedKeyAdmissionService(jdbc,keys);
            assertNull(jdbc.queryForObject("SELECT owner_uid FROM serving.service_api_key WHERE id=?",Long.class,legacyKey));assertTrue(keys.authenticate(legacySecret).isEmpty());assertThrows(ServingSecurity.UnauthorizedException.class,()->keys.rotate(legacyKey,"reviewer"));assertThrows(ServingSecurity.UnauthorizedException.class,()->applications.approve(legacyApp,"reviewer"));assertTrue(applications.mine(42).isEmpty());
            assertFalse(keys.consumeQuota(legacyKey));assertEquals("revoked",keys.revoke(legacyKey).status());assertEquals("rejected",applications.reject(legacyApp,"reviewer","request a fresh binding").status());
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE serving.service_api_key SET owner_uid=42,owner_access_version=3 WHERE id=?",legacyKey));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE serving.service_api_key_application SET requester_uid=42,requester_access_version=3 WHERE id=?",legacyApp));
            var user=new AtomicReference<TrustedUser>();f.request(r->user.set(TrustedUser.require(r)));
            var issued=keys.create(new ApiKeyCreateRequest("quota",7,null),user.get(),"alice");long id=issued.summary().id();assertEquals(42,jdbc.queryForObject("SELECT owner_uid FROM serving.service_api_key WHERE id=?",Long.class,id));assertEquals(3,jdbc.queryForObject("SELECT owner_access_version FROM serving.service_api_key WHERE id=?",Long.class,id));
            var created=parallel(24,()->{try{return admissions.create(issued.secret(),ASSET,null,"quota-test");}catch(PublishedKeyAdmissionService.QuotaExceeded denied){return null;}}).stream().filter(Objects::nonNull).toList();assertEquals(7,created.size());assertEquals(7,jdbc.queryForObject("SELECT quota_used FROM serving.service_api_key WHERE id=?",Integer.class,id));assertEquals(7,jdbc.queryForObject("SELECT count(*) FROM serving.published_key_admission WHERE key_id=?",Integer.class,id));
            var chosen=created.getFirst();byte[] claim=input(chosen);
            var claims=parallel(24,()->{try{replica.redeem(claim);return true;}catch(PublishedKeyAdmissionService.Conflict denied){return false;}});assertEquals(1,claims.stream().filter(Boolean::booleanValue).count());assertThrows(PublishedKeyAdmissionService.Conflict.class,()->new PublishedKeyAdmissionService(jdbc,keys).redeem(claim));
            var bad=new PublishedKeyAdmissionService.Reference(created.get(1).reference().admissionId(),"f".repeat(64),created.get(1).reference().parametersSha256());assertThrows(PublishedKeyAdmissionService.Conflict.class,()->admissions.redeem(PublishedKeyJson.bytes(bad.map())));assertNotNull(admissions.redeem(input(created.get(1))));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE serving.published_key_admission SET parameters_sha256=? WHERE id=?","f".repeat(64),chosen.reference().admissionId()));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE serving.published_key_admission SET redeemed_at_epoch_millis=NULL WHERE id=?",chosen.reference().admissionId()));
            var app=applications.request(new ApiKeyApplicationRequest("requested","approval",20,null),user.get());assertEquals(1,applications.mine(42).size());assertTrue(applications.mine(99).isEmpty());
            var approved=parallel(2,()->{try{return applications.approve(app.id(),"reviewer");}catch(ApiKeyApplicationService.ConflictException denied){return null;}}).stream().filter(Objects::nonNull).toList();assertEquals(1,approved.size());long approvedId=approved.getFirst().summary().id();assertEquals("alice",approved.getFirst().summary().ownerUsername());assertEquals(42,jdbc.queryForObject("SELECT owner_uid FROM serving.service_api_key WHERE id=?",Long.class,approvedId));assertEquals(3,jdbc.queryForObject("SELECT owner_access_version FROM serving.service_api_key WHERE id=?",Long.class,approvedId));assertEquals("reviewer",jdbc.queryForObject("SELECT created_by FROM serving.service_api_key WHERE id=?",String.class,approvedId));
            var rotations=parallel(2,()->{try{return keys.rotate(approvedId,"reviewer");}catch(ApiKeyService.ConflictException denied){return null;}}).stream().filter(Objects::nonNull).toList();assertEquals(1,rotations.size());assertEquals(3,jdbc.queryForObject("SELECT owner_access_version FROM serving.service_api_key WHERE id=?",Long.class,rotations.getFirst().summary().id()));assertTrue(keys.authenticate(approved.getFirst().secret()).isEmpty());
            var rollback=keys.create(new ApiKeyCreateRequest("rollback",3,null),user.get(),"alice");admin.execute("REVOKE INSERT ON serving.published_key_admission FROM key_runtime");assertThrows(org.springframework.dao.DataAccessException.class,()->admissions.create(rollback.secret(),ASSET,null,"rollback"));assertEquals(0,jdbc.queryForObject("SELECT quota_used FROM serving.service_api_key WHERE id=?",Integer.class,rollback.summary().id()));admin.execute("GRANT INSERT ON serving.published_key_admission TO key_runtime");
            var revocable=admissions.create(rollback.secret(),ASSET,null,"revoked");keys.revoke(rollback.summary().id());assertThrows(PublishedKeyAdmissionService.Conflict.class,()->admissions.redeem(input(revocable)));
            var pending=applications.request(new ApiKeyApplicationRequest("pending","stale binding",3,null),user.get());
            f.active.set(false);long before=jdbc.queryForObject("SELECT count(*) FROM serving.service_api_key",Long.class);assertThrows(ServingSecurity.ForbiddenException.class,()->keys.rotate(rotations.getFirst().summary().id(),"reviewer"));assertThrows(ServingSecurity.ForbiddenException.class,()->keys.create(new ApiKeyCreateRequest("inactive",3,null),user.get(),"alice"));assertThrows(ServingSecurity.ForbiddenException.class,()->applications.approve(pending.id(),"reviewer"));assertEquals("requested",applications.find(pending.id()).status());assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM serving.service_api_key",Long.class));f.active.set(true);
            var expiring=admissions.create(rotations.getFirst().secret(),ASSET,null,"expiry-test");long expires=jdbc.queryForObject("SELECT expires_at_epoch_millis FROM serving.published_key_admission WHERE id=?",Long.class,expiring.reference().admissionId());long now=jdbc.queryForObject("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint",Long.class);
            String stored=jdbc.queryForObject("SELECT string_agg(canonical_admission,'') FROM serving.published_key_admission",String.class);for(String secret:List.of(password,runtimePassword,issued.secret(),approved.getFirst().secret(),rotations.getFirst().secret()))assertFalse(stored.contains(secret));assertFalse(stored.contains("compactProof"));
            var usage=new ApiUsageService(jdbc,new SimpleMeterRegistry());usage.record("orders_api","alice","api_key",503,1,"DEPENDENCY_UNAVAILABLE",new ApiUsageService.Context(expiring.keyId(),expiring.reference().admissionId(),expiring.requestId(),42));assertEquals(503,jdbc.queryForObject("SELECT status_code FROM serving.service_api_usage WHERE admission_id=?",Integer.class,expiring.reference().admissionId()));assertEquals(42,jdbc.queryForObject("SELECT owner_uid FROM serving.service_api_usage WHERE admission_id=?",Long.class,expiring.reference().admissionId()));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("SET session_replication_role=replica"));
            long retained=jdbc.queryForObject("SELECT count(*) FROM serving.published_key_admission",Long.class);
            // Only the guarded ephemeral admin simulates historical rows. All constraints remain active;
            // the trigger is bypassed only for the insertion of already-expired fixture history.
            String oldCanonical=jdbc.queryForObject("SELECT canonical_admission FROM serving.published_key_admission WHERE id=?",String.class,chosen.reference().admissionId());
            long oldCreated=now-25*3600000L,oldExpires=oldCreated+30000;
            admin.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{
                try(var control=connection.createStatement()){
                    control.execute("SET session_replication_role=replica");
                    try(var statement=connection.prepareStatement("""
                        WITH historic AS (
                            SELECT md5('published-key-history-'||i)::uuid id,
                              ?::jsonb || jsonb_build_object('admissionId',md5('published-key-history-'||i)::uuid::text,'createdAtEpochMillis',?::bigint,'expiresAtEpochMillis',?::bigint) payload
                            FROM generate_series(1,1002) i
                        ), canonical AS (SELECT id,payload::text body FROM historic)
                        INSERT INTO serving.published_key_admission(id,key_id,owner_uid,owner_username,owner_access_version,asset_id,asset_version,canonical_admission,admission_sha256,parameters_sha256,created_at_epoch_millis,expires_at_epoch_millis)
                        SELECT id,?,42,'alice',3,11,'v1',body,encode(sha256(convert_to(body,'UTF8')),'hex'),?,?,? FROM canonical
                        """)){
                        statement.setString(1,oldCanonical);statement.setLong(2,oldCreated);statement.setLong(3,oldExpires);statement.setLong(4,chosen.keyId());statement.setString(5,chosen.reference().parametersSha256());statement.setLong(6,oldCreated);statement.setLong(7,oldExpires);assertEquals(1002,statement.executeUpdate());
                    }finally{control.execute("SET session_replication_role=origin");}
                }return null;
            });
            assertEquals(1000,admissions.purgeExpired());assertEquals(retained+2,jdbc.queryForObject("SELECT count(*) FROM serving.published_key_admission",Long.class));assertEquals(2,replica.purgeExpired());assertEquals(retained,jdbc.queryForObject("SELECT count(*) FROM serving.published_key_admission",Long.class));assertNotNull(jdbc.queryForObject("SELECT id FROM serving.published_key_admission WHERE id=?",UUID.class,expiring.reference().admissionId()));
            Thread.sleep(Math.max(1,expires-now+20));assertThrows(PublishedKeyAdmissionService.Conflict.class,()->admissions.redeem(input(expiring)));assertNull(jdbc.queryForObject("SELECT redeemed_at_epoch_millis FROM serving.published_key_admission WHERE id=?",Long.class,expiring.reference().admissionId()));
        }
    }
}
