package com.aetheris.ingestion.security;

import com.aetheris.ingestion.web.GlobalExceptionHandler;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual filters + MVC/advice with a signed loopback auth replacement, not Tomcat or a platform DB. */
class IngestionV2MvcTest {
    @RestController static class Probe {
        final ResourceAuthorization authorization;final AtomicInteger calls=new AtomicInteger();
        Probe(ResourceAuthorization authorization){this.authorization=authorization;}
        @GetMapping("/api/ingestion/tasks/{id}") Map<String,Object> read(@PathVariable long id){authorization.require("ingestion_task",id,"view");calls.incrementAndGet();return Map.of("id",id);}
        @PostMapping("/api/ingestion/tasks/{id}/run") Map<String,Object> run(@PathVariable long id,@Valid @RequestBody Input body){authorization.require("ingestion_task",id,"execute");calls.incrementAndGet();return Map.of("name",body.name());}
        @GetMapping("/api/ingestion/identity-error") void identityError(){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"missing");}
    }
    record Input(@NotBlank String name) { }
    MockMvc mvc(V2IngestionFixture f,Probe p){return MockMvcBuilders.standaloneSetup(p).setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new GatewayIdentityInterceptor(f.authorization)).addFilters(f.workload(),f.direct()).build();}
    @Test void actualPermissionDenialAndUnavailableRemain403And503ThroughAdvice() throws Exception {
        try(var f=new V2IngestionFixture()){
            var p=new Probe(f.authorization);var mvc=mvc(f,p);
            assertEquals(200,mvc.perform(get("/api/ingestion/tasks/8").header(SignedHttpClient.USER_PROOF,f.token())).andReturn().getResponse().getStatus());
            assertEquals(403,mvc.perform(get("/api/ingestion/tasks/9").header(SignedHttpClient.USER_PROOF,f.token())).andReturn().getResponse().getStatus());
            f.permissionFaultOnly=true;f.fault="unsigned";
            assertEquals(503,mvc.perform(get("/api/ingestion/tasks/8").header(SignedHttpClient.USER_PROOF,f.token())).andReturn().getResponse().getStatus());
            f.fault="";assertEquals(401,mvc.perform(get("/api/ingestion/identity-error").header(SignedHttpClient.USER_PROOF,f.token())).andReturn().getResponse().getStatus());
            assertEquals(1,p.calls.get());
        }
    }
    @Test void missingWrongAudienceDisabledAndAvMismatchNeverReachBusiness() throws Exception {
        try(var f=new V2IngestionFixture()){
            var p=new Probe(f.authorization);var mvc=mvc(f,p);
            assertEquals(401,mvc.perform(get("/api/ingestion/tasks/8").header("X-User-Name","admin")).andReturn().getResponse().getStatus());
            assertEquals(401,mvc.perform(get("/api/ingestion/tasks/8").header(SignedHttpClient.USER_PROOF,f.token("workflow",42,3))).andReturn().getResponse().getStatus());
            assertEquals(403,mvc.perform(get("/api/ingestion/tasks/8").header(SignedHttpClient.USER_PROOF,f.token("ingestion",42,2))).andReturn().getResponse().getStatus());
            f.active.set(false);assertEquals(403,mvc.perform(get("/api/ingestion/tasks/8").header(SignedHttpClient.USER_PROOF,f.token())).andReturn().getResponse().getStatus());
            assertEquals(0,p.calls.get());
        }
    }
    @Test void validMutationRequiresPermissionAndDtoFailureRemains400() throws Exception {
        try(var f=new V2IngestionFixture()){
            var p=new Probe(f.authorization);var mvc=mvc(f,p);
            assertEquals(200,mvc.perform(post("/api/ingestion/tasks/8/run").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"name\":\"run\"}")).andReturn().getResponse().getStatus());
            for(String body:List.of("{\"name\":\"\"}","{"))assertEquals(400,mvc.perform(post("/api/ingestion/tasks/8/run").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
            f.mutate.set(false);assertEquals(403,mvc.perform(post("/api/ingestion/tasks/8/run").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"name\":\"run\"}")).andReturn().getResponse().getStatus());
            assertEquals(1,p.calls.get());
        }
    }
    @Test void legacyInternalAndUnlistedV2EndpointsRemainClosed() throws Exception {
        try(var f=new V2IngestionFixture()) {
            var p=new Probe(f.authorization);var mvc=mvc(f,p);
            for(String path:List.of("/internal/ingestion/execute","/internal/v2/executions/unlisted")) {
                int status=mvc.perform(post(path).header("X-Internal-Token","old").contentType("application/json").content("{}")).andReturn().getResponse().getStatus();
                // Non-v2 internal paths fail canonicalization; canonical but unlisted v2 routes are 404.
                assertEquals(path.startsWith("/internal/v2/")?404:400,status);
            }
            assertEquals(0,p.calls.get());assertTrue(f.requests.isEmpty());
        }
    }
}
