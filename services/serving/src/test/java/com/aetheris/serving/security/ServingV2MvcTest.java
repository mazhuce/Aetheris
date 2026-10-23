package com.aetheris.serving.security;

import com.aetheris.serving.config.ServingV2SecurityConfiguration;
import com.aetheris.serving.web.GlobalExceptionHandler;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ServingV2MvcTest {
    @RestController static class Endpoints {
        @GetMapping("/api/serving/test") String user(HttpServletRequest request){return ((TrustedUser)request.getAttribute(TrustedUser.ATTRIBUTE)).proof().username();}
        @GetMapping("/api/analytics/test") String unavailable(){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"权限服务不可用");}
        @PostMapping("/api/serving/v1/apis/ORDERS/query") String key(HttpServletRequest request){assertNull(request.getAttribute(TrustedUser.ATTRIBUTE));return "key-admission-only";}
    }
    @Test void actualFilterAndAdvicePreserveTypedUserAndDependencyStatus()throws Exception{
        try(var f=new V2ServingFixture()){
            var mvc=MockMvcBuilders.standaloneSetup(new Endpoints()).setControllerAdvice(new GlobalExceptionHandler())
                    .addFilters(ServingV2SecurityConfiguration.directFilter(f.verifier,f.sessions())).build();
            mvc.perform(get("/api/serving/test").header(SignedHttpClient.USER_PROOF,f.token()).header("X-User-Role","admin")).andExpect(status().isOk()).andExpect(content().string("alice"));
            mvc.perform(get("/api/analytics/test").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/api/serving/test").header(SignedHttpClient.USER_PROOF,f.token("metadata",42,3))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/serving/test").header("X-Aetheris-Identity",new ServiceIdentitySigner("old-test-identity-key-at-least-32-bytes").sign("admin"))).andExpect(status().isUnauthorized());
            f.active.set(false);
            mvc.perform(get("/api/serving/test").header(SignedHttpClient.USER_PROOF,f.token())).andExpect(status().isForbidden());
        }
    }
    @Test void apiKeyBypassIsOnlyExactPostAndNeverCreatesUserContext()throws Exception{
        try(var f=new V2ServingFixture()){
            var mvc=MockMvcBuilders.standaloneSetup(new Endpoints()).addFilters(ServingV2SecurityConfiguration.directFilter(f.verifier,f.sessions())).build();
            mvc.perform(post("/api/serving/v1/apis/ORDERS/query").header("X-API-Key","fixture-key").header(SignedHttpClient.USER_PROOF,f.token("metadata",42,3))).andExpect(status().isOk()).andExpect(content().string("key-admission-only"));
            for(String path:java.util.List.of("/api/serving/v1/apis/ORDERS/query/","/api/serving/v1/apis/ORDERS/publish","/api/serving/v1/query","/api/serving/api-keys"))
                mvc.perform(post(path).header("X-API-Key","fixture-key")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/serving/v1/apis/ORDERS/query").header("X-API-Key","fixture-key")).andExpect(status().isUnauthorized());
            assertTrue(f.requests.isEmpty());
        }
    }
    @Test void expiredProofChangedDispatchAndUnlistedPathDoNotReachHandler()throws Exception{
        try(var f=new V2ServingFixture()){
            Filter filter=ServingV2SecurityConfiguration.directFilter(f.verifier,f.sessions());
            long now=f.clock.instant().getEpochSecond();
            var oldSigner=new ProtocolSigner("user",KeyPurpose.USER,"gateway",f.keys.get("user").getPrivate(),f.registry,java.time.Clock.offset(f.clock,java.time.Duration.ofSeconds(-70)),TimePolicy.defaults());
            String expired=oldSigner.sign(new UserProof(2,"gateway","serving",42,"alice",3,now-70,now-10,"expired-serving"));
            var request=new MockHttpServletRequest("GET","/api/serving/test");request.addHeader(SignedHttpClient.USER_PROOF,expired);
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->fail("Expired reached handler"));assertEquals(401,response.getStatus());
            for(DispatcherType type:java.util.List.of(DispatcherType.FORWARD,DispatcherType.INCLUDE,DispatcherType.ERROR,DispatcherType.ASYNC)){
                request=new MockHttpServletRequest("POST","/api/serving/v1/apis/ORDERS/query");request.setDispatcherType(type);request.addHeader("X-API-Key","fixture-key");
                response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->fail("Dispatch reached handler"));assertEquals(400,response.getStatus());
            }
            request=new MockHttpServletRequest("GET","/api/admin/test");request.addHeader(SignedHttpClient.USER_PROOF,f.token());
            response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->fail("Unlisted reached handler"));assertEquals(403,response.getStatus());
        }
    }
}
