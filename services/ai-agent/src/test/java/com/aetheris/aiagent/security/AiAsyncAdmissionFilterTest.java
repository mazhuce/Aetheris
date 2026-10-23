package com.aetheris.aiagent.security;

import com.aetheris.security.servlet.*;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AiAsyncAdmissionFilterTest {
    @Test void admissionIdentityRequestIdAndAsyncWrapperCannotBeOverwritten() throws Exception {
        var keys=new AiTestKeys();var request=keys.authenticated("alice");
        var identity=TrustedUser.require(request);var gate=new AiAsyncAdmissionFilter(keys.verifier,(u,n,v)->true,keys.clock);
        var admitted=new AtomicReference<HttpServletRequest>();
        gate.doFilter(request,new MockHttpServletResponse(),(r,s)->admitted.set((HttpServletRequest)r));
        var wrapper=admitted.get();assertSame(identity,TrustedUser.require(wrapper));
        assertEquals("request-alice",wrapper.getAttribute(AiAsyncAdmissionFilter.REQUEST_ID));
        assertEquals("request-alice",wrapper.getHeader("X-Request-Id"));
        for(String name:java.util.List.of(TrustedUser.ATTRIBUTE,AiAsyncAdmissionFilter.REQUEST_ID)) {
            assertThrows(IllegalStateException.class,()->wrapper.setAttribute(name,"forged"));
            assertThrows(IllegalStateException.class,()->wrapper.removeAttribute(name));
        }
        assertThrows(IllegalStateException.class,()->wrapper.startAsync(request,new MockHttpServletResponse()));
        request.setDispatcherType(DispatcherType.ASYNC);
        var passed=new AtomicInteger();gate.doFilter(request,new MockHttpServletResponse(),(r,s)->{passed.incrementAndGet();assertSame(identity,TrustedUser.require((HttpServletRequest)r));});
        assertEquals(1,passed.get());
        request.setAttribute(TrustedUser.ATTRIBUTE,TrustedUser.require(keys.authenticated("bob")));
        var response=new MockHttpServletResponse();gate.doFilter(request,response,(r,s)->fail("changed identity resumed"));assertEquals(403,response.getStatus());
    }
    @Test void rawPathMethodQueryAdmissionAndDispatcherAreRechecked() throws Exception {
        var keys=new AiTestKeys();var gate=new AiAsyncAdmissionFilter(keys.verifier,(u,n,v)->true,keys.clock);
        for(String mutation:java.util.List.of("path","method","query","forward","include","error")) {
            var request=keys.authenticated("alice");gate.doFilter(request,new MockHttpServletResponse(),(r,s)->{});request.setDispatcherType(DispatcherType.ASYNC);
            switch(mutation){case "path"->request.setRequestURI("/api/ai/conversations");case "method"->request.setMethod("DELETE");case "query"->request.setQueryString("subject=bob");
                case "forward"->request.setDispatcherType(DispatcherType.FORWARD);case "include"->request.setDispatcherType(DispatcherType.INCLUDE);case "error"->request.setDispatcherType(DispatcherType.ERROR);}
            var response=new MockHttpServletResponse();gate.doFilter(request,response,(r,s)->fail("invalid dispatch accepted"));
            assertEquals(mutation.equals("forward")||mutation.equals("include")||mutation.equals("error")?400:403,response.getStatus(),mutation);
        }
        var noAdmission=keys.authenticated("alice");noAdmission.setDispatcherType(DispatcherType.ASYNC);
        var response=new MockHttpServletResponse();gate.doFilter(noAdmission,response,(r,s)->fail("no admission"));assertEquals(403,response.getStatus());
    }
    @Test void sessionUnavailableOnResumeFails503WithoutExecutingChain() throws Exception {
        var keys=new AiTestKeys();var gate=new AiAsyncAdmissionFilter(keys.verifier,(u,n,v)->{throw new IllegalStateException("not returned to client");},keys.clock);
        var request=keys.authenticated("alice");gate.doFilter(request,new MockHttpServletResponse(),(r,s)->{});request.setDispatcherType(DispatcherType.ASYNC);
        var response=new MockHttpServletResponse();gate.doFilter(request,response,(r,s)->fail("unavailable session"));
        assertEquals(503,response.getStatus());assertFalse(response.getContentAsString().contains("not returned"));
    }
}
