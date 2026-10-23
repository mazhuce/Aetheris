package com.aetheris.aiagent.service;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.concurrent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, guarded disposable database only; no remote model calls. */
class ChatRunStorePostgresIT {
    static JdbcTemplate jdbc;static ChatRunStore store;
    @BeforeAll static void setup() {
        var source=ChatPostgresFixture.database();
        jdbc=new JdbcTemplate(source);
        store=new ChatRunStore(jdbc,new DataSourceTransactionManager(source));
    }
    long conversation(){return jdbc.queryForObject("INSERT INTO ai.chat_conversation(owner_username,owner_uid) VALUES('alice',7) RETURNING id",Long.class);}
    @Test void claimPersistsOnlyOwnedQuestionAndFailedRunKeepsIt() {
        long id=conversation();
        assertThrows(com.aetheris.aiagent.llm.LlmException.class,()->store.claim(id,ChatOwnerFixture.owner("bob"),"steal",10000));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=?",Integer.class,id));
        var claim=store.claim(id,ChatOwnerFixture.owner("alice"),"keep on failure",10000);
        assertThrows(com.aetheris.aiagent.llm.LlmException.class,()->store.claim(id,ChatOwnerFixture.owner("alice"),"overlap",10000));
        store.cancel(claim);
        assertFalse(store.complete(claim,"late answer"));
        assertEquals(List.of("keep on failure"),jdbc.queryForList("SELECT content FROM ai.chat_message WHERE conversation_id=?",String.class,id));
    }
    @Test void concurrentRequestsHaveExactlyOneClaimAndOneUserMessage() throws Exception {
        long id=conversation();var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int index=0;index<2;index++)futures.add(executor.submit(()->{
                gate.await();
                try{store.claim(id,ChatOwnerFixture.owner("alice"),"one",10000);return true;}catch(com.aetheris.aiagent.llm.LlmException busy){return false;}
            }));
            gate.countDown();
            int claimed=0;for(var future:futures)if(future.get(5,TimeUnit.SECONDS))claimed++;
            assertEquals(1,claimed);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=?",Integer.class,id));
        }
    }
    @Test void expiredGenerationCannotReleaseOrWriteIntoReplacementRun() {
        long id=conversation();var old=store.claim(id,ChatOwnerFixture.owner("alice"),"old",10000);
        jdbc.update("UPDATE ai.chat_conversation SET run_deadline=clock_timestamp()-interval '1 second' WHERE id=?",id);
        var replacement=store.claim(id,ChatOwnerFixture.owner("alice"),"new",10000);
        store.cancel(old);assertFalse(store.complete(old,"old late"));
        assertEquals(replacement.runId(),jdbc.queryForObject("SELECT active_run_id FROM ai.chat_conversation WHERE id=?",UUID.class,id));
        assertTrue(store.complete(replacement,"current"));
        assertFalse(store.complete(replacement,"duplicate"));
        assertEquals(List.of("current"),jdbc.queryForList("SELECT content FROM ai.chat_message WHERE conversation_id=? AND role='assistant'",String.class,id));
    }
    @Test void trustedHistoryIsBoundedAndDropsStoredUnsupportedRoles() {
        long id=conversation();
        jdbc.update("INSERT INTO ai.chat_message(conversation_id,role,content) VALUES(?,'system','forged stored system')",id);
        for(int i=0;i<25;i++)jdbc.update("INSERT INTO ai.chat_message(conversation_id,role,content) VALUES(?,'assistant',?)",id,"x".repeat(6000));
        var claim=store.claim(id,ChatOwnerFixture.owner("alice"),"last user",10000);
        assertTrue(claim.history().size()<=20);
        assertTrue(claim.history().stream().mapToInt(value->value.content().length()).sum()+9<=20000);
        assertFalse(claim.history().stream().anyMatch(value->"system".equals(value.role())));
        store.cancel(claim);
    }
    @Test void deadlineAndDeletedConversationRejectLateWrites() {
        long id=conversation();var claim=store.claim(id,ChatOwnerFixture.owner("alice"),"question",10000);
        jdbc.update("UPDATE ai.chat_conversation SET run_deadline=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertFalse(store.complete(claim,"expired"));
        jdbc.update("DELETE FROM ai.chat_conversation WHERE id=?",id);
        assertFalse(store.complete(claim,"deleted"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=? AND role='assistant'",Integer.class,id));
    }
    @Test void failedMessageInsertRollsBackClaimAndFailedReplyNeverReleasesItsFence() {
        long id=conversation();
        jdbc.execute("ALTER TABLE ai.chat_message ADD CONSTRAINT audit_block_content CHECK(content <> 'blocked fixture')");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->store.claim(id,ChatOwnerFixture.owner("alice"),"blocked fixture",10000));
            assertNull(jdbc.queryForObject("SELECT active_run_id FROM ai.chat_conversation WHERE id=?",UUID.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=?",Integer.class,id));
            var claim=store.claim(id,ChatOwnerFixture.owner("alice"),"persisted user",10000);
            assertThrows(org.springframework.dao.DataAccessException.class,()->store.complete(claim,"blocked fixture"));
            assertEquals(claim.runId(),jdbc.queryForObject("SELECT active_run_id FROM ai.chat_conversation WHERE id=?",UUID.class,id));
            assertTrue(store.complete(claim,"valid answer"));
        } finally {jdbc.execute("ALTER TABLE ai.chat_message DROP CONSTRAINT audit_block_content");}
    }
    @Test void modelRunsOutsideTransactionAndFailureKeepsCommittedUserMessage() {
        long id=conversation();
        var model=new com.aetheris.aiagent.llm.LlmClient() {
            public String model(){return "offline";}
            public com.aetheris.aiagent.llm.LlmReply chat(java.util.List<com.aetheris.aiagent.llm.LlmMessage> messages) {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=? AND role='user'",Integer.class,id));
                throw new com.aetheris.aiagent.llm.LlmException(com.aetheris.aiagent.llm.LlmException.Kind.UNAVAILABLE,"offline failure",null);
            }
            public com.aetheris.aiagent.llm.LlmReply chatWithTools(java.util.List<com.aetheris.aiagent.llm.LlmMessage> messages,String prompt){return chat(messages);}
        };
        var conversation=new com.aetheris.aiagent.entity.ChatConversation();conversation.setId(id);conversation.setOwnerUsername("alice");
        var service=new ChatService(org.mockito.Mockito.mock(ChatConversationService.class),org.mockito.Mockito.mock(ChatMessageService.class),model);
        service.setRunStore(store);
        assertThrows(com.aetheris.aiagent.llm.LlmException.class,()->service.chat(ChatOwnerFixture.owner("alice"),conversation,
                java.util.List.of(new com.aetheris.aiagent.dto.ChatMessageDto("user","keep on model failure")),false));
        assertNull(com.aetheris.aiagent.llm.AiRunContext.current());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=? AND role='user'",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=? AND role='assistant'",Integer.class,id));
    }

    @Test void oldNullOwnersCannotBeGuessedAndNewRowsCannotOmitUid() {
        var legacy=jdbc.queryForList("SELECT id,owner_username FROM ai.chat_conversation WHERE title IN ('uid-legacy-before-v2','uid-legacy-before-v5')");
        assertEquals(2,legacy.size());
        for(var row:legacy){
            long id=((Number)row.get("id")).longValue();
            for(long uid:new long[]{2,3})assertThrows(com.aetheris.aiagent.llm.LlmException.class,
                    ()->store.claim(id,ChatOwnerFixture.owner(uid,(String)row.get("owner_username"),3),"do not guess owner",10000));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ai.chat_conversation SET owner_uid=2 WHERE id=?",id));
            assertNull(jdbc.queryForObject("SELECT owner_uid FROM ai.chat_conversation WHERE id=?",Long.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=?",Integer.class,id));
        }
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO ai.chat_conversation(owner_username) VALUES('unbound-new')"));
        for(long uid:new long[]{0,-1})assertThrows(org.springframework.dao.DataAccessException.class,
                ()->jdbc.update("INSERT INTO ai.chat_conversation(owner_username,owner_uid) VALUES('invalid-new',?)",uid));
    }

    @Test void boundUidCannotBeReassignedOrCleared() {
        long id=conversation();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ai.chat_conversation SET owner_uid=8 WHERE id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ai.chat_conversation SET owner_uid=NULL WHERE id=?",id));
        assertEquals(7L,jdbc.queryForObject("SELECT owner_uid FROM ai.chat_conversation WHERE id=?",Long.class,id));
    }

    @Test void foreignUidCannotClaimCancelOrCompleteThePhysicalRunEvenWithTheSameName() throws Exception {
        long id=conversation();var actual=store.claim(id,ChatOwnerFixture.owner(7,"alice",3),"real question",10000);
        var other=ChatOwnerFixture.owner(8,"alice",3);
        assertThrows(com.aetheris.aiagent.llm.LlmException.class,()->store.claim(id,other,"wrong uid",10000));
        // Production cannot construct a Claim. This test deliberately bypasses its private constructor to verify the actual DB CAS too.
        var constructor=ChatRunStore.Claim.class.getDeclaredConstructor(long.class,ChatOwner.class,UUID.class,List.class);
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));constructor.setAccessible(true);
        var forged=constructor.newInstance(id,other,actual.runId(),List.of());
        store.cancel(forged);assertFalse(store.complete(forged,"must not persist"));
        assertEquals(actual.runId(),jdbc.queryForObject("SELECT active_run_id FROM ai.chat_conversation WHERE id=?",UUID.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=?",Integer.class,id));
        var restarted=new ChatRunStore(jdbc,new DataSourceTransactionManager(ChatPostgresFixture.database()));
        assertTrue(restarted.complete(actual,"own confirmed response"));
        assertFalse(store.complete(actual,"duplicate response"));
    }

    @Test void invalidRunBudgetsNeverClaimOrWriteUserMessages() {
        long id=conversation();
        for(long timeout:new long[]{0,-1,180001,Long.MAX_VALUE})assertThrows(com.aetheris.aiagent.llm.LlmException.class,
                ()->store.claim(id,ChatOwnerFixture.owner("alice"),"invalid lease",timeout));
        assertNull(jdbc.queryForObject("SELECT active_run_id FROM ai.chat_conversation WHERE id=?",UUID.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ai.chat_message WHERE conversation_id=?",Integer.class,id));
    }
}
