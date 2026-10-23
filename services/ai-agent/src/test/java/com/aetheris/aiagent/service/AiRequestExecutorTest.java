package com.aetheris.aiagent.service;
import com.aetheris.aiagent.llm.*;
import com.aetheris.aiagent.security.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.*;
import org.springframework.web.context.request.async.DeferredResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class AiRequestExecutorTest {
    @Test void legacyWorkerDtoAndBareHeaderCannotBePromotedIntoSnapshot() {
        var raw=new MockHttpServletRequest();raw.setAttribute(IdentityInterceptor.IDENTITY_ATTRIBUTE,new AetherisIdentity(7L,"alice"));raw.addHeader("X-Aetheris-Identity","legacy");
        assertThrows(IllegalStateException.class,()->AiRequestExecutor.snapshot(raw));
    }
    final AiTestKeys keys=new AiTestKeys();
    MockHttpServletRequest request(String user) {
        try {var request=keys.authenticated(user);request.addHeader("Authorization","must-not-cross-thread");return request;}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    ResponseEntity<Map<String,Object>> await(DeferredResult<ResponseEntity<Map<String,Object>>> result) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(result.getResult()==null&&System.nanoTime()<end)Thread.sleep(5);
        assertNotNull(result.getResult());
        return (ResponseEntity<Map<String,Object>>)result.getResult();
    }
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();AiRunContext.clear();}
    @Test void reusedWorkerGetsOnlyAnImmutableVerifiedSnapshot() throws Exception {
        try(var executor=new AiRequestExecutor(new AiBudgetLimits(2,2,10000,1000,1000,1000,1,1))) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var alice=request("alice");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request("evil-parent")));
            var first=executor.submit(alice,8L,()->{
                entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}
                assertNull(RequestContextHolder.getRequestAttributes());
                var worker=AiProofContext.current();
                assertEquals("alice",worker.user().proof().username());
                assertEquals(7,worker.user().proof().uid());
                assertEquals("request-alice",worker.requestId());
                assertFalse(worker.compact().contains("must-not-cross-thread"));
                assertNotNull(AiRunContext.current());
                return ResponseEntity.ok(Map.of("user",worker.user().proof().username()));
            });
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            alice.setAttribute(com.aetheris.security.servlet.TrustedUser.ATTRIBUTE,com.aetheris.security.servlet.TrustedUser.require(keys.authenticated("bob")));
            release.countDown();assertEquals("alice",await(first).getBody().get("user"));
            var second=executor.submit(request("bob"),9L,()->{
                assertNull(RequestContextHolder.getRequestAttributes());
                var worker=AiProofContext.current();
                return ResponseEntity.ok(Map.of("user",worker.user().proof().username()));
            });
            assertEquals("bob",await(second).getBody().get("user"));
        }
    }
    @Test void concurrencyAndQueueHaveFixedCapacity() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=new AiRequestExecutor(new AiBudgetLimits(2,2,10000,1000,1000,1000,1,1))) {
            executor.submit(request("alice"),8L,()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return ResponseEntity.ok(Map.of());});
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            executor.submit(request("bob"),9L,()->ResponseEntity.ok(Map.of()));
            assertEquals(429,await(executor.submit(request("carol"),10L,()->ResponseEntity.ok(Map.of()))).getStatusCode().value());
            release.countDown();
        } finally {release.countDown();}
    }
}
