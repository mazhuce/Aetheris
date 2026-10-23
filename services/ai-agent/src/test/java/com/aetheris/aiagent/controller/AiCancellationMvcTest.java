package com.aetheris.aiagent.controller;
import com.aetheris.aiagent.service.*;
import com.aetheris.aiagent.llm.*;
import com.aetheris.aiagent.entity.ChatConversation;
import com.aetheris.aiagent.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import jakarta.servlet.AsyncEvent;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiCancellationMvcTest {
    @Test void servletTimeoutPreventsLateAssistantWriteEvenIfModelIgnoresInterrupt() throws Exception {cancelledRun(true);}
    @Test void detectedDisconnectPreventsLateAssistantWrite() throws Exception {cancelledRun(false);}
    void cancelledRun(boolean timeout) throws Exception {
        var conversations=mock(ChatConversationService.class);var store=mock(ChatRunStore.class);var model=mock(LlmClient.class);
        var owned=new ChatConversation();owned.setId(8L);owned.setOwnerUsername("alice");
        when(conversations.getOne(any())).thenReturn(owned);
        var claim=mock(ChatRunStore.Claim.class);when(claim.history()).thenReturn(List.of());
        when(store.claim(eq(8L),eq(ChatOwnerFixture.owner("alice")),eq("question"),anyLong())).thenReturn(claim);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var cleaned=new CountDownLatch(1);
        when(model.chat(any())).thenAnswer(call->{
            entered.countDown();
            boolean done=false;while(!done)try{done=release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            return new LlmReply("must not persist","fake",1,1);
        });
        doAnswer(call->{cleaned.countDown();return null;}).when(store).cancel(any());
        var service=new ChatService(conversations,mock(ChatMessageService.class),model);service.setRunStore(store);
        var limiter=mock(UserRateLimiter.class);when(limiter.tryAcquire(any(ChatOwner.class))).thenReturn(true);
        var controller=new ChatController(service,limiter);
        try(var executor=new AiRequestExecutor(new AiBudgetLimits(2,2,10000,1000,1000,1000,1,0))) {
            controller.setRequestExecutor(executor);
            var mvc=MockMvcBuilders.standaloneSetup(controller).build();
            var result=mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                    .requestAttr(com.aetheris.security.servlet.TrustedUser.ATTRIBUTE,com.aetheris.security.servlet.TrustedUser.require(new AiTestKeys().authenticated("alice")))
                    .header("X-Aetheris-Identity","signed-alice")
                    .content("{\"conversationId\":8,\"messages\":[{\"role\":\"user\",\"content\":\"question\"}]}"))
                    .andExpect(request().asyncStarted()).andReturn();
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            var async=(MockAsyncContext)result.getRequest().getAsyncContext();
            for(var listener:async.getListeners()) {
                if(timeout)listener.onTimeout(new AsyncEvent(async));
                else listener.onError(new AsyncEvent(async,new java.io.IOException("simulated disconnect")));
            }
            release.countDown();assertTrue(cleaned.await(2,TimeUnit.SECONDS));
            verify(store,never()).complete(any(),anyString());
            if(timeout)mvc.perform(asyncDispatch(result)).andExpect(status().isGatewayTimeout());
        } finally {release.countDown();}
    }
}
