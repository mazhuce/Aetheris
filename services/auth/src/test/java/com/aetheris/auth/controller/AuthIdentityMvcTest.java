package com.aetheris.auth.controller;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.security.*;
import com.aetheris.auth.service.*;
import com.aetheris.auth.web.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthIdentityMvcTest {
    final AuthUserService users=mock(AuthUserService.class);
    final PermissionDecisionService decisions=mock(PermissionDecisionService.class);
    final V2ProofFixture proof=new V2ProofFixture();
    MockMvc mvc;
    @BeforeEach void setup() {
        var sessions=new AuthSessionValidator(users);
        mvc=MockMvcBuilders.standaloneSetup(new SelfPermissionController(decisions))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new AuthIdentityInterceptor())
                .addFilters(proof.workload((c,n,e)->true,sessions),proof.direct(sessions)).build();
        AuthUser alice=new AuthUser();alice.setId(42L);alice.setUsername("alice");alice.setStatus("active");alice.setAccessVersion(3L);
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(decisions.allowedIds(eq("alice"),eq("quality_rule"),eq("execute"),anyMap())).thenReturn(Set.of(1L));
    }
    @Test void bareHeadersAndLegacyIdentityCannotEstablishSubject() throws Exception {
        mvc.perform(get("/api/auth/me/resource-permissions").header("X-User-Name","admin").header("X-User-Role","admin")
                .header("X-Internal-Token","old-token").header("X-Aetheris-Identity","old.hmac"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(decisions);
    }
    @Test void actualVerifiedProofUsesAliceDespiteForgedHeader() throws Exception {
        mvc.perform(get("/api/auth/me/resource-permissions").param("resourceType","quality_rule").param("action","execute").param("ids","1,2")
                        .header("X-Aetheris-User-Proof",proof.user("auth",42,"alice",3)).header("X-User-Name","admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowedIds[0]").value(1));
        verify(decisions).allowedIds("alice","quality_rule","execute",Map.of(1L,Map.of(),2L,Map.of()));
    }
    @Test void disabledWrongUidWrongAudienceOrAccessVersionCannotAuthenticate() throws Exception {
        for(var entry:List.of(Map.entry(proof.user("auth",7,"alice",3),403),Map.entry(proof.user("auth",42,"alice",2),403),Map.entry(proof.user("system",42,"alice",3),401),Map.entry("broken.proof.value",401)))
            mvc.perform(get("/api/auth/me/resource-permissions").header("X-Aetheris-User-Proof",entry.getKey())).andExpect(status().is(entry.getValue()));
        users.findByUsername("alice").orElseThrow().setStatus("disabled");
        mvc.perform(get("/api/auth/me/resource-permissions").header("X-Aetheris-User-Proof",proof.user("auth",42,"alice",3))).andExpect(status().isForbidden());
        verifyNoInteractions(decisions);
    }
    @Test void oldInternalTokenCannotReachOldEndpoint() throws Exception {
        mvc.perform(post("/internal/sessions/check").header("X-Internal-Token","old-token").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
