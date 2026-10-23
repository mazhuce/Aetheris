package com.aetheris.aiagent.service;

import com.aetheris.aiagent.controller.ChatController;
import com.aetheris.aiagent.entity.*;
import com.aetheris.aiagent.llm.*;
import com.aetheris.aiagent.mapper.*;
import com.aetheris.aiagent.security.AiAsyncAdmissionFilter;
import com.aetheris.aiagent.security.AiTestKeys;
import com.aetheris.aiagent.service.impl.*;
import com.aetheris.aiagent.web.GlobalExceptionHandler;
import com.aetheris.security.protocol.UserProof;
import com.aetheris.security.servlet.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Actual PostgreSQL/MyBatis/short transactions + Ed25519/filters/MVC; deterministic local model only.
 * The mutable SessionValidator fixture represents deleting UID2 and recreating the same name as UID3;
 * it is deliberately not presented as an actual auth-service or full application deployment test.
 */
class ChatOwnerIdentityPostgresIT {
    private static DriverManagerDataSource database;
    private static JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AiTestKeys keys = new AiTestKeys();
    private final AtomicLong liveUid = new AtomicLong(2), liveAv = new AtomicLong(3);
    private final List<Long> ownedConversations = new ArrayList<>();
    private final RecordingModel model = new RecordingModel();
    private String username;
    private AnnotationConfigApplicationContext context;
    private MockMvc mvc;

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions { }

    @BeforeAll static void database() {
        database = ChatPostgresFixture.database();
        jdbc = new JdbcTemplate(database);
    }

    @BeforeEach void application() throws Exception {
        username = "reused-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatConversationMapper.class); configuration.addMapper(ChatMessageMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(database); factory.setConfiguration(configuration); factory.afterPropertiesSet();
        var sessions = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var conversations = new ChatConversationServiceImpl();
        var messages = new ChatMessageServiceImpl();
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> database);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(database));
        context.registerBean(ChatConversationMapper.class, () -> sessions.getMapper(ChatConversationMapper.class));
        context.registerBean(ChatMessageMapper.class, () -> sessions.getMapper(ChatMessageMapper.class));
        context.registerBean(ChatConversationService.class, () -> conversations);
        context.registerBean(ChatMessageService.class, () -> messages);
        context.registerBean(LlmClient.class, () -> model);
        context.registerBean(AiBudgetLimits.class, () -> new AiBudgetLimits(2, 0, 4096, 1000, 1000, 10000, 1, 2));
        context.registerBean(ChatRunStore.class, () -> new ChatRunStore(jdbc, context.getBean(PlatformTransactionManager.class)));
        context.registerBean(ChatService.class, () -> new ChatService(conversations, messages, model));
        context.registerBean(AiRequestExecutor.class, () -> new AiRequestExecutor(context.getBean(AiBudgetLimits.class)));
        context.registerBean(UserRateLimiter.class, () -> new UserRateLimiter(20, Duration.ofMinutes(1)));
        context.registerBean(ChatController.class, () -> new ChatController(context.getBean(ChatService.class), context.getBean(UserRateLimiter.class)));
        context.refresh();
        SessionValidator sessionsLive = (uid, name, av) -> uid == liveUid.get() && username.equals(name) && av == liveAv.get();
        var direct = new DirectUserProofFilter("ai-agent", keys.verifier, sessionsLive,
                (method, path) -> DirectRoutePolicy.Decision.PROTECTED);
        // Same dispatcher ownership as the application: Direct only on REQUEST; AI gate reauthenticates ASYNC.
        Filter requestOnly = (request, response, chain) -> {
            if (request.getDispatcherType() == DispatcherType.REQUEST) direct.doFilter(request, response, chain);
            else chain.doFilter(request, response);
        };
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(ChatController.class))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .addFilters(requestOnly, new AiAsyncAdmissionFilter(keys.verifier, sessionsLive, keys.clock)).build();
    }

    @AfterEach void cleanup() {
        if (context != null) context.close();
        for (long id : ownedConversations) {
            jdbc.update("DELETE FROM ai.chat_message WHERE conversation_id=?", id);
            jdbc.update("DELETE FROM ai.chat_conversation WHERE id=?", id);
        }
        assertNull(AiRunContext.current());
        assertNull(com.aetheris.aiagent.security.AiProofContext.current());
    }

    @Test void newUidCannotListOldSameNameConversations() throws Exception {
        create(); liveUid.set(3);
        var response = mvc.perform(get("/api/ai/conversations").header("X-Aetheris-User-Proof", token(3, 3)))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(0, json.readTree(response.getContentAsString()).size(), "UID3 must not inherit UID2's same-name conversations");
    }

    @Test void newUidCannotReadOldSameNameMessages() throws Exception {
        long id = create(); assertEquals(200, chat(id, 2, 3, "old-private-history").getStatus()); liveUid.set(3);
        var response = mvc.perform(get("/api/ai/conversations/" + id + "/messages").header("X-Aetheris-User-Proof", token(3, 3)))
                .andReturn().getResponse();
        assertAll(() -> assertEquals(404, response.getStatus()),
                () -> assertFalse(response.getContentAsString().contains("old-private-history")));
    }

    @Test void newUidCannotContinueOldSameNameConversationOrSupplyItsHistoryToModel() throws Exception {
        long id = create(); assertEquals(200, chat(id, 2, 3, "old-private-history").getStatus()); liveUid.set(3);
        var response = chat(id, 3, 3, "new account question");
        assertAll(() -> assertTrue(Set.of(400, 404).contains(response.getStatus()), "Existing missing-conversation behavior is 400/404"),
                () -> assertEquals(1, model.inputs.size(), "New UID must be rejected before any second model invocation"),
                () -> assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=?", Integer.class, id)));
    }

    @Test void newUidCannotDeleteOldSameNameConversation() throws Exception {
        long id = create(); assertEquals(200, chat(id, 2, 3, "old-private-history").getStatus()); liveUid.set(3);
        var response = mvc.perform(delete("/api/ai/conversations/" + id).header("X-Aetheris-User-Proof", token(3, 3)))
                .andReturn().getResponse();
        assertAll(() -> assertEquals(404, response.getStatus()),
                () -> assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai.chat_conversation WHERE id=?", Integer.class, id)),
                () -> assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=?", Integer.class, id)));
    }

    @Test void sameUidAndNewAccessVersionRetainsOwnConversation() throws Exception {
        long id = create(); assertEquals(200, chat(id, 2, 3, "own history").getStatus()); liveAv.set(4);
        assertEquals(200, mvc.perform(get("/api/ai/conversations/" + id + "/messages")
                .header("X-Aetheris-User-Proof", token(2, 4))).andReturn().getResponse().getStatus());
        assertEquals(200, chat(id, 2, 4, "after password change").getStatus());
        assertEquals(204, mvc.perform(delete("/api/ai/conversations/" + id).header("X-Aetheris-User-Proof", token(2, 4)))
                .andReturn().getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=?", Integer.class, id));
    }

    @Test void replacedUidIsRejectedByLiveSessionBeforeAnyConversationAccess() throws Exception {
        create(); liveUid.set(3);
        assertEquals(403, mvc.perform(get("/api/ai/conversations").header("X-Aetheris-User-Proof", token(2, 3)))
                .andReturn().getResponse().getStatus());
        assertEquals(0, model.inputs.size());
    }

    @Test void conversationOwnerComesFromVerifiedUidRatherThanJsonOrLegacyHeaders() throws Exception {
        var response=mvc.perform(post("/api/ai/conversations").header("X-Aetheris-User-Proof",token(2,3))
                .header("X-User-Name","other-owner").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerUid\":3,\"ownerUsername\":\"other-owner\"}")).andReturn().getResponse();
        assertEquals(200,response.getStatus());long id=json.readTree(response.getContentAsString()).path("id").asLong();
        assertTrue(id>0);ownedConversations.add(id);
        assertEquals(2L,jdbc.queryForObject("SELECT owner_uid FROM ai.chat_conversation WHERE id=?",Long.class,id));
        assertEquals(username,jdbc.queryForObject("SELECT owner_username FROM ai.chat_conversation WHERE id=?",String.class,id));
        assertFalse(json.readTree(response.getContentAsString()).has("ownerUid"));
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"admin","alice"})
    void legacyNullUidHistoryCannotBeReadContinuedOrDeletedByMatchingCurrentName(String oldName) throws Exception {
        username=oldName;
        long id=jdbc.queryForObject("SELECT id FROM ai.chat_conversation WHERE owner_uid IS NULL AND owner_username=? ORDER BY id LIMIT 1",Long.class,oldName);
        var listed=mvc.perform(get("/api/ai/conversations").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse();
        assertEquals(200,listed.getStatus());
        assertFalse(java.util.stream.StreamSupport.stream(json.readTree(listed.getContentAsString()).spliterator(),false)
                .anyMatch(item->item.path("id").asLong()==id),"Matching legacy name must not expose an unbound conversation");
        var messages=mvc.perform(get("/api/ai/conversations/"+id+"/messages").header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse();
        assertEquals(404,messages.getStatus());
        assertTrue(Set.of(400,404).contains(chat(id,2,3,"must not claim legacy history").getStatus()));
        assertEquals(404,mvc.perform(delete("/api/ai/conversations/"+id).header("X-Aetheris-User-Proof",token(2,3))).andReturn().getResponse().getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai.chat_conversation WHERE id=? AND owner_uid IS NULL",Integer.class,id));
        assertEquals(0,model.inputs.size());
    }

    private long create() throws Exception {
        var response = mvc.perform(post("/api/ai/conversations").header("X-Aetheris-User-Proof", token(2, 3)))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus()); long id = json.readTree(response.getContentAsString()).path("id").asLong();
        assertTrue(id > 0); ownedConversations.add(id); return id;
    }

    private MockHttpServletResponse chat(long id, long uid, long av, String question) throws Exception {
        var result = mvc.perform(post("/api/ai/chat").header("X-Aetheris-User-Proof", token(uid, av))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("conversationId", id,
                        "messages", List.of(Map.of("role", "user", "content", question)), "dataMode", false)))).andReturn();
        if (!result.getRequest().isAsyncStarted()) return result.getResponse();
        assertNotNull(result.getAsyncResult(5000), "Local offline model must complete within the fixture budget");
        return mvc.perform(asyncDispatch(result)).andReturn().getResponse();
    }

    private String token(long uid, long av) {
        long now = keys.clock.instant().getEpochSecond();
        return keys.user.sign(new UserProof(2, "gateway", "ai-agent", uid, username, av, now, now + 60, "uid-fixture-" + UUID.randomUUID()));
    }

    private static class RecordingModel implements LlmClient {
        private final List<List<LlmMessage>> inputs = new CopyOnWriteArrayList<>();
        public LlmReply chat(List<LlmMessage> messages) { inputs.add(List.copyOf(messages)); return new LlmReply("offline answer", "fixture", 1, 1); }
        public LlmReply chatWithTools(List<LlmMessage> messages, String prompt) { return chat(messages); }
        public String model() { return "fixture"; }
    }
}
