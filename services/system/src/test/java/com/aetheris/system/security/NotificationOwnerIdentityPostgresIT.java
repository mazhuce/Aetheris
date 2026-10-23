package com.aetheris.system.security;

import com.aetheris.security.protocol.*;
import com.aetheris.security.servlet.*;
import com.aetheris.system.config.SystemV2SecurityConfiguration;
import com.aetheris.system.controller.AlertCenterController;
import com.aetheris.system.controller.InternalNotificationController;
import com.aetheris.system.controller.NotificationController;
import com.aetheris.system.mapper.NotificationMapper;
import com.aetheris.system.service.NotificationService;
import com.aetheris.system.service.OperationsInboxStore;
import com.aetheris.system.entity.Notification;
import com.aetheris.system.service.impl.NotificationServiceImpl;
import com.aetheris.system.web.GlobalExceptionHandler;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Real disposable PostgreSQL/Flyway/MyBatis + Ed25519/filters/MVC.
 * Session and mayManage are explicit local fixtures, not a live auth application.
 * No existing database fallback: this class intentionally fails without its guarded fixture environment.
 */
class NotificationOwnerIdentityPostgresIT {
    private static DriverManagerDataSource database;
    private static JdbcTemplate jdbc;
    private static long legacyId;
    private final Clock clock = Clock.systemUTC();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong liveUid = new AtomicLong(2), liveAv = new AtomicLong(3);
    private final AtomicBoolean mayManage = new AtomicBoolean(false);
    private final AtomicBoolean permissionUnavailable = new AtomicBoolean(false);
    private final AtomicInteger permissionCalls = new AtomicInteger();
    private final List<Long> ownedNotifications = new ArrayList<>();
    private final Set<UUID> ownedEvents=ConcurrentHashMap.newKeySet();
    private String username;
    private AnnotationConfigApplicationContext context;
    private ValidatorFactory validation;
    private MockMvc mvc;
    private MockMvc secondMvc;
    private NotificationService notifications;
    private OperationsInboxStore store;
    private AuthPermissionClient management;
    private ProtocolSigner systemSigner;
    private ProtocolVerifier verifier;
    private ProtocolSigner userSigner, workflowSigner;

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions { }

    @BeforeAll static void database() {
        String url = System.getenv("AETHERIS_TEST_POSTGRES_URL");
        String password = System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit")
                || password == null || password.length() < 24)
            throw new IllegalStateException("Requires isolated loopback aetheris_audit PostgreSQL");
        database = new DriverManagerDataSource(url, "audit_admin", password);
        jdbc = new JdbcTemplate(database);
        assertEquals("aetheris_audit", jdbc.queryForObject("SELECT current_database()", String.class));
        assertNull(jdbc.queryForObject("SELECT to_regclass('system.flyway_schema_history')::text",String.class),"Upgrade fixture requires a fresh system schema before staging V7");
        var old=Flyway.configure().dataSource(database).schemas("system").defaultSchema("system")
                .locations("classpath:db/migration").target("7").load();
        old.migrate();
        legacyId=jdbc.queryForObject("INSERT INTO system.notification(recipient,source,level,title) VALUES('admin','workflow','critical','unbound-before-v8') RETURNING id",Long.class);
        var flyway = Flyway.configure().dataSource(database).schemas("system").defaultSchema("system").locations("classpath:db/migration").load();
        flyway.migrate();
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(0, flyway.info().pending().length);
        assertEquals(8L, Arrays.stream(flyway.info().applied()).filter(m -> m.getVersion() != null).count());
        assertNull(jdbc.queryForObject("SELECT recipient_uid FROM system.notification WHERE id=?",Long.class,legacyId));
        assertNull(jdbc.queryForObject("SELECT inbox_kind FROM system.notification WHERE id=?",String.class,legacyId));
    }

    @BeforeEach void application() throws Exception {
        username = "reused-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var generator = KeyPairGenerator.getInstance("Ed25519");
        Map<String, KeyPair> keys = new HashMap<>();
        for (String name : List.of("user", "workflow", "system","metadata")) keys.put(name, generator.generateKeyPair());
        var registry = new KeyRegistry(List.of(
                new KeyRegistry.Entry("user-fixture", KeyPurpose.USER, "gateway", keys.get("user").getPublic()),
                new KeyRegistry.Entry("workflow-fixture", KeyPurpose.WORKLOAD, "workflow", keys.get("workflow").getPublic()),
                new KeyRegistry.Entry("system-fixture", KeyPurpose.WORKLOAD, "system", keys.get("system").getPublic()),
                new KeyRegistry.Entry("metadata-fixture", KeyPurpose.WORKLOAD, "metadata", keys.get("metadata").getPublic())));
        verifier = new ProtocolVerifier(registry, clock, TimePolicy.defaults());
        userSigner = new ProtocolSigner("user-fixture", KeyPurpose.USER, "gateway", keys.get("user").getPrivate(), registry, clock, TimePolicy.defaults());
        workflowSigner = new ProtocolSigner("workflow-fixture", KeyPurpose.WORKLOAD, "workflow", keys.get("workflow").getPrivate(), registry, clock, TimePolicy.defaults());
        systemSigner = new ProtocolSigner("system-fixture", KeyPurpose.WORKLOAD, "system", keys.get("system").getPrivate(), registry, clock, TimePolicy.defaults());
        metadataSigner = new ProtocolSigner("metadata-fixture", KeyPurpose.WORKLOAD, "metadata", keys.get("metadata").getPrivate(), registry, clock, TimePolicy.defaults());

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true); configuration.addMapper(NotificationMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(database); factory.setConfiguration(configuration); factory.afterPropertiesSet();
        var sessions = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> database);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(database));
        // Register the actual mapper bean: ServiceImpl's generic baseMapper is autowired by Spring.
        context.registerBean(NotificationMapper.class, () -> sessions.getMapper(NotificationMapper.class));
        context.registerBean(NotificationService.class, NotificationServiceImpl::new);
        context.refresh();
        notifications = context.getBean(NotificationService.class);
        validation = Validation.buildDefaultValidatorFactory();
        management = mock(AuthPermissionClient.class);
        doAnswer(call->{permissionCalls.incrementAndGet();
            if(permissionUnavailable.get())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Permission fixture unavailable");
            if(!mayManage.get())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Management fixture denied");
            return null;
        }).when(management).requireMayManage(any(TrustedUser.class));
        store=new OperationsInboxStore(jdbc,context.getBean(PlatformTransactionManager.class));
        mvc=replica(store);
        secondMvc=replica(new OperationsInboxStore(jdbc,context.getBean(PlatformTransactionManager.class)));
    }

    private MockMvc replica(OperationsInboxStore replicaStore) {
        var internalBodies = new SystemInternalRequests(validation.getValidator());
        SessionValidator active = (uid, name, av) -> uid == liveUid.get() && username.equals(name) && av == liveAv.get();
        var workloads = new WorkloadProofFilter("system", verifier, systemSigner,
                SystemV2SecurityConfiguration.workloadRoutes(), new PostgresNonceStore(database, "system", clock),
                active, clock, TimePolicy.defaults());
        var direct = new DirectUserProofFilter("system", verifier, active, SystemV2SecurityConfiguration.directRoutes());
        return MockMvcBuilders.standaloneSetup(new NotificationController(notifications),
                        new AlertCenterController(notifications, management,replicaStore), new InternalNotificationController(replicaStore, internalBodies))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .addMappedInterceptors(new String[]{"/api/system/**"}, new SystemIdentityInterceptor(new IdentityVerifier()))
                .addFilters(workloads, direct).build();
    }

    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (validation != null) validation.close();
        for (long id : ownedNotifications) jdbc.update("DELETE FROM system.notification WHERE id=?", id);
        for(UUID event:ownedEvents)jdbc.update("DELETE FROM system.notification WHERE event_id=?",event);
    }

    @Test void recreatedUidCannotListOldSameNamePersonalNotifications() throws Exception {
        long id = create(); liveUid.set(3);
        var response = mvc.perform(get("/api/system/notifications").header("X-Aetheris-User-Proof", token(3, 3)))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertFalse(containsId(response, id), "New UID must not inherit the old account's private notification");
    }

    @Test void recreatedUidCannotCountOldSameNameUnreadNotifications() throws Exception {
        create(); liveUid.set(3);
        var response = mvc.perform(get("/api/system/notifications/unread-count").header("X-Aetheris-User-Proof", token(3, 3)))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(0L, json.readTree(response.getContentAsString()).path("count").asLong(-1),
                "Unread counts must use stable UID ownership too");
    }

    @Test void recreatedUidCannotMarkTheOldSameNameNotificationRead() throws Exception {
        long id = create(); liveUid.set(3);
        var response = mvc.perform(put("/api/system/notifications/" + id + "/read")
                .header("X-Aetheris-User-Proof", token(3, 3))).andReturn().getResponse();
        assertAll(() -> assertEquals(404, response.getStatus()), () -> assertFalse(isRead(id)));
    }

    @Test void recreatedUidCannotMarkAllOldSameNameNotificationsRead() throws Exception {
        long first = create(), second = create(); liveUid.set(3);
        var response = mvc.perform(put("/api/system/notifications/read-all")
                .header("X-Aetheris-User-Proof", token(3, 3))).andReturn().getResponse();
        assertAll(() -> assertEquals(204, response.getStatus()), () -> assertFalse(isRead(first)), () -> assertFalse(isRead(second)));
    }

    @Test void sameUidWithNewAccessVersionKeepsOwnNotificationAndCanMarkItRead() throws Exception {
        long id = create(); liveAv.set(4);
        var response = mvc.perform(get("/api/system/notifications").header("X-Aetheris-User-Proof", token(2, 4)))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertTrue(containsId(response, id));
        assertEquals(204, mvc.perform(put("/api/system/notifications/" + id + "/read")
                .header("X-Aetheris-User-Proof", token(2, 4))).andReturn().getResponse().getStatus());
        assertTrue(isRead(id));
    }

    @Test void replacedUidProofIsRejectedByLiveSessionBeforeReadingOrMutating() throws Exception {
        long id = create(); liveUid.set(3);
        assertEquals(403, mvc.perform(get("/api/system/notifications").header("X-Aetheris-User-Proof", token(2, 3)))
                .andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(put("/api/system/notifications/read-all").header("X-Aetheris-User-Proof", token(2, 3)))
                .andReturn().getResponse().getStatus());
        assertFalse(isRead(id));
    }

    @Test void ordinaryRecreatedSameNameUserCannotEnterTheOperationsAlertCenter() throws Exception {
        long id = createOperations(); liveUid.set(3); mayManage.set(false);
        var response = mvc.perform(get("/api/system/alerts").header("X-Aetheris-User-Proof", token(3, 3)))
                .andReturn().getResponse();
        assertAll(() -> assertEquals(403, response.getStatus(), "Operations inbox must require current mayManage without a username fallback"),
                () -> assertFalse(response.getContentAsString().contains("private-event-")), () -> assertFalse(isRead(id)));
    }

    @Test void legacyNullRowsAreIsolatedAndCannotBeGuessedIntoEitherInbox() throws Exception {
        username="admin";
        var personal=mvc.perform(get("/api/system/notifications").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse();
        assertEquals(200,personal.getStatus());assertFalse(containsId(personal,legacyId));
        assertEquals(404,mvc.perform(put("/api/system/notifications/"+legacyId+"/read").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse().getStatus());
        mayManage.set(true);
        var operations=mvc.perform(get("/api/system/alerts").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse();
        assertEquals(200,operations.getStatus());assertFalse(containsId(operations,legacyId));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE system.notification SET inbox_kind='personal',recipient_uid=2 WHERE id=?",legacyId));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO system.notification(recipient,source,title) VALUES('admin','workflow','unbound-new')"));
        assertNull(jdbc.queryForObject("SELECT inbox_kind FROM system.notification WHERE id=?",String.class,legacyId));
        assertFalse(isRead(legacyId));
    }

    @Test void personalUidAndEventPayloadAreImmutableInTheDatabase() throws Exception {
        long personal=create(),operations=createOperations();
        for(String assignment:List.of("recipient_uid=3","recipient_uid=NULL","recipient='other'","inbox_kind=NULL"))
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE system.notification SET "+assignment+" WHERE id=?",personal));
        for(String assignment:List.of("title='changed'","source='quality'","event_id='00000000-0000-0000-0000-000000000000'","event_sha256='"+"b".repeat(64)+"'"))
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE system.notification SET "+assignment+" WHERE id=?",operations));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO system.notification(recipient,source,title,inbox_kind,recipient_uid) VALUES('other','workflow','invalid','personal',0)"));
        assertEquals(2L,jdbc.queryForObject("SELECT recipient_uid FROM system.notification WHERE id=?",Long.class,personal));
        mayManage.set(true);
        var visible=mvc.perform(get("/api/system/alerts").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse();
        assertEquals(200,visible.getStatus());assertTrue(containsId(visible,operations));assertFalse(containsId(visible,personal));
    }

    @Test void twoReplicasConcurrentlyAcceptOneCallerEventExactlyOnce() throws Exception {
        UUID event=UUID.randomUUID();byte[] body=eventBody(event,"concurrent-event");
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(4)){
            List<Future<?>> pending=new ArrayList<>();
            for(int i=0;i<8;i++){MockMvc receiver=i%2==0?mvc:secondMvc;pending.add(workers.submit(()->{
                start.await();signed(receiver,body,"workflow",Nonce.random(),202);return null;
            }));}
            start.countDown();for(var result:pending)result.get(15,TimeUnit.SECONDS);
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM system.notification WHERE source='workflow' AND event_id=?",Integer.class,event));
        remember(event,"workflow");
    }

    @Test void lostConfirmationUsesFreshNonceSameEventAndDifferentBodyConflicts() throws Exception {
        UUID event=UUID.randomUUID();byte[] body=eventBody(event,"confirmation-lost");String firstNonce=Nonce.random();
        signed(mvc,body,"workflow",firstNonce,202);remember(event,"workflow");
        signed(secondMvc,body,"workflow",Nonce.random(),202); // the caller did not rely on its first acknowledgement
        signed(secondMvc,body,"workflow",firstNonce,409); // transport replay is separate from event idempotency
        signed(secondMvc,eventBody(event,"different content"),"workflow",Nonce.random(),409);
        var node=json.readTree(body);var reordered=new LinkedHashMap<String,Object>();
        for(String field:List.of("linkPath","message","title","level","sourceId","eventId"))reordered.put(field,node.get(field));
        signed(mvc,json.writeValueAsBytes(reordered),"workflow",Nonce.random(),202);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM system.notification WHERE source='workflow' AND event_id=?",Integer.class,event));
    }

    @Test void callerNamespacesAreSeparateAndMetadataHasOnlyTheFixedInbox() throws Exception {
        UUID event=UUID.randomUUID();byte[] body=eventBody(event,"caller-separated");
        signed(mvc,body,"workflow",Nonce.random(),202);remember(event,"workflow");
        signed(secondMvc,body,"metadata",Nonce.random(),202);remember(event,"metadata");
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM system.notification WHERE event_id=? AND inbox_kind='operations' AND recipient='operations' AND recipient_uid IS NULL",Integer.class,event));
        var invalid=json.readTree(body);((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put("recipient","admin");
        signed(mvc,json.writeValueAsBytes(invalid),"metadata",Nonce.random(),400);
    }

    @Test void realDatabaseFailureHasSigned503WithoutAcceptedOrLeakedSql() throws Exception {
        UUID event=UUID.randomUUID();
        jdbc.execute("ALTER TABLE system.notification ADD CONSTRAINT audit_reject_event CHECK(title<>'reject-inbox-fixture')");
        try {
            var response=signed(mvc,eventBody(event,"reject-inbox-fixture"),"workflow",Nonce.random(),503);
            assertFalse(response.getContentAsString().contains("audit_reject_event"));assertFalse(response.getContentAsString().contains("INSERT INTO"));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM system.notification WHERE event_id=?",Integer.class,event));
        }finally{jdbc.execute("ALTER TABLE system.notification DROP CONSTRAINT audit_reject_event");}
    }

    @Test void everyOperationsReadAndAckUsesCurrentPermissionAndKeeps503Distinct() throws Exception {
        long id=createOperations();String proof=token(2,3);
        for(boolean unavailable:List.of(false,true)){
            permissionUnavailable.set(unavailable);mayManage.set(false);
            var requests=List.of(get("/api/system/alerts"),get("/api/system/alerts/stats"),
                    put("/api/system/alerts/"+id+"/acknowledge"),put("/api/system/alerts/acknowledge-all"));
            for(var request:requests)assertEquals(unavailable?503:403,mvc.perform(request.header("X-Aetheris-User-Proof",proof)).andReturn().getResponse().getStatus());
        }
        assertEquals(8,permissionCalls.get());assertFalse(isRead(id));
        permissionUnavailable.set(false);mayManage.set(true);
        assertEquals(200,mvc.perform(get("/api/system/alerts").header("X-Aetheris-User-Proof",proof)).andReturn().getResponse().getStatus());
        mayManage.set(false);assertEquals(403,mvc.perform(get("/api/system/alerts").header("X-Aetheris-User-Proof",proof)).andReturn().getResponse().getStatus());
    }

    @Test void sharedAcknowledgementIsNotPersonalReadAndNeverResetsItsActorOrTime() throws Exception {
        long operations=createOperations(),personal=create();mayManage.set(true);
        assertEquals(404,mvc.perform(put("/api/system/notifications/"+operations+"/read").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse().getStatus());
        assertEquals(204,mvc.perform(put("/api/system/notifications/read-all").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse().getStatus());
        assertTrue(isRead(personal));assertFalse(isRead(operations));
        var first=mvc.perform(put("/api/system/alerts/"+operations+"/acknowledge").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse();
        assertEquals(200,first.getStatus());assertEquals(1,json.readTree(first.getContentAsString()).path("acknowledged").asInt());
        var stamped=jdbc.queryForMap("SELECT ack_uid,ack_time FROM system.notification WHERE id=?",operations);assertEquals(2L,((Number)stamped.get("ack_uid")).longValue());
        liveUid.set(3);
        var duplicate=mvc.perform(put("/api/system/alerts/"+operations+"/acknowledge").header("X-Aetheris-User-Proof",token(3,3))).andReturn().getResponse();
        assertEquals(200,duplicate.getStatus());assertEquals(0,json.readTree(duplicate.getContentAsString()).path("acknowledged").asInt());
        assertEquals(stamped,jdbc.queryForMap("SELECT ack_uid,ack_time FROM system.notification WHERE id=?",operations));
        assertEquals(404,mvc.perform(put("/api/system/alerts/"+personal+"/acknowledge").header("X-Aetheris-User-Proof",token(3,3))).andReturn().getResponse().getStatus());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE system.notification SET is_read=false,ack_uid=NULL,ack_time=NULL WHERE id=?",operations));
    }

    @Test void batchAcknowledgementIsBoundedTo500AndSignalsMoreWithoutTouchingOtherInboxes() throws Exception {
        long personal=create();String title="batch-"+UUID.randomUUID();List<Object[]> rows=new ArrayList<>();
        for(int i=0;i<502;i++)rows.add(new Object[]{"workflow",title,UUID.randomUUID(),"a".repeat(64)});
        jdbc.batchUpdate("INSERT INTO system.notification(recipient,inbox_kind,source,title,level,event_id,event_sha256) VALUES('operations','operations',?,?,'info',?,?)",rows);
        ownedNotifications.addAll(jdbc.queryForList("SELECT id FROM system.notification WHERE title=?",Long.class,title));
        mayManage.set(true);
        int[] expected={500,2,0};
        for(int i=0;i<expected.length;i++){
            var response=mvc.perform(put("/api/system/alerts/acknowledge-all").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse();
            assertEquals(200,response.getStatus());var result=json.readTree(response.getContentAsString());
            assertEquals(expected[i],result.path("acknowledged").asInt());assertEquals(i==0,result.path("hasMore").asBoolean());
        }
        assertFalse(isRead(personal));assertFalse(isRead(legacyId));
        assertEquals(502,jdbc.queryForObject("SELECT count(*) FROM system.notification WHERE title=? AND ack_uid=2 AND ack_time IS NOT NULL AND is_read",Integer.class,title));
    }

    private void remember(UUID event,String caller){
        ownedNotifications.add(jdbc.queryForObject("SELECT id FROM system.notification WHERE event_id=? AND source=?",Long.class,event,caller));
    }

    private long create() {
        var notification=new Notification();notification.setRecipient(username);notification.setRecipientUid(2L);notification.setInboxKind("personal");
        notification.setSource("workflow");notification.setLevel("info");notification.setTitle("private-event-"+UUID.randomUUID());notification.setIsRead(false);
        assertTrue(notifications.save(notification));assertNotNull(notification.getId());ownedNotifications.add(notification.getId());
        return notification.getId();
    }

    private ProtocolSigner metadataSigner;
    private long createOperations() throws Exception {
        String title = "private-event-" + UUID.randomUUID();
        UUID event=UUID.randomUUID();byte[] body=eventBody(event,title);
        signed(mvc,body,"workflow",Nonce.random(),202);
        Long id = jdbc.queryForObject("SELECT id FROM system.notification WHERE source='workflow' AND event_id=?", Long.class,event);
        assertNotNull(id); ownedNotifications.add(id); assertFalse(isRead(id));
        return id;
    }
    private byte[] eventBody(UUID id,String title) throws Exception {
        ownedEvents.add(id);
        Map<String,Object> body=new LinkedHashMap<>();body.put("eventId",id.toString());body.put("sourceId",17);body.put("level","critical");
        body.put("title",title);body.put("message","Old account's private execution detail");body.put("linkPath","/development/workflow");
        return json.writeValueAsBytes(body);
    }
    private MockHttpServletResponse signed(MockMvc receiver,byte[] body,String caller,String nonce,int expected) throws Exception {
        long now = clock.instant().getEpochSecond();
        String proof = (caller.equals("metadata")?metadataSigner:workflowSigner).sign(new WorkloadRequestProof(2, caller, "system", "POST", "/internal/v2/notifications",
                BodyHash.sha256(body), nonce, now, now + 30, "system.notification.record", BodyHash.userProof(null)));
        var response = receiver.perform(post("/internal/v2/notifications").header("X-Aetheris-Workload-Proof", proof)
                .contentType("application/json").content(body)).andReturn().getResponse();
        assertEquals(expected, response.getStatus()); if(expected==202)assertEquals(0, response.getContentAsByteArray().length);
        verifier.verifyResponse(response.getHeader("X-Aetheris-Response-Proof"),
                new ExpectedResponse("system", caller, nonce, expected, BodyHash.sha256(response.getContentAsByteArray())));
        return response;
    }

    private boolean isRead(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_read FROM system.notification WHERE id=?", Boolean.class, id));
    }

    private boolean containsId(MockHttpServletResponse response, long id) throws Exception {
        var values = json.readTree(response.getContentAsString()); assertTrue(values.isArray());
        return java.util.stream.StreamSupport.stream(values.spliterator(), false).anyMatch(value -> value.path("id").asLong() == id);
    }

    private String token(long uid, long av) {
        long now = clock.instant().getEpochSecond();
        return userSigner.sign(new UserProof(2, "gateway", "system", uid, username, av, now, now + 60, "notification-fixture-" + UUID.randomUUID()));
    }
}
