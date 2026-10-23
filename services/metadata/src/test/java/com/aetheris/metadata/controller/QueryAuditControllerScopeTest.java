package com.aetheris.metadata.controller;

import com.aetheris.metadata.security.*;
import com.aetheris.metadata.service.QueryAuditService;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class QueryAuditControllerScopeTest {
    private final MetadataProofFixture proof = new MetadataProofFixture();
    private final AuthPermissionClient permissions = mock(AuthPermissionClient.class);
    private final QueryAuditService audit = mock(QueryAuditService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        when(permissions.check(eq("alice"),eq("metric"),isNull(),eq("view"),eq(Map.of())))
                .thenReturn(AuthPermissionClient.Access.ALLOWED);
        when(permissions.check(eq("alice"),eq("query_audit"),isNull(),eq("view"),eq(Map.of())))
                .thenReturn(new AuthPermissionClient.Access(false,"No global audit grant"));
        when(audit.summary(anyLong(),anyBoolean(),anyInt(),nullable(Long.class))).thenReturn(Map.of("total",1));
        mvc = MockMvcBuilders.standaloneSetup(new QueryAuditController(audit,new MetadataSecurity(permissions)))
                .setControllerAdvice(new MetadataAccessExceptionHandler())
                .addFilters(proof.direct((uid,name,av)->true)).build();
    }

    @Test void explicitGlobalAuditDecisionReadsGlobalSummary() throws Exception {
        when(permissions.check("alice","query_audit",null,"view",Map.of())).thenReturn(AuthPermissionClient.Access.ALLOWED);
        mvc.perform(get("/api/metadata/query-audit/summary").header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk());
        verify(audit).summary(42L,true,30,null);
    }

    @Test void forgedAdminAndGlobalParametersNeverExpandOrdinaryUsersScope() throws Exception {
        mvc.perform(get("/api/metadata/query-audit/summary?global=true&username=admin&days=7&slowMs=500")
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata"))
                        .header("X-User-Name","admin").header("X-User-Role","admin"))
                .andExpect(status().isOk());
        verify(audit).summary(42L,false,7,500L);
    }

    @Test void unavailableGlobalDecisionIs503WithoutReadingEitherScope() throws Exception {
        when(permissions.check("alice","query_audit",null,"view",Map.of()))
                .thenReturn(AuthPermissionClient.Access.unavailable("Unavailable"));
        mvc.perform(get("/api/metadata/query-audit/summary").header(SignedHttpClient.USER_PROOF,proof.user("metadata"))
                        .header("X-Request-Id","forged-error-id"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.requestId").value("metadata-v2-test"));
        verifyNoInteractions(audit);
    }

    @Test void deniedExistingReadCapabilityDoesNotCheckGlobalOrReadRows() throws Exception {
        when(permissions.check("alice","metric",null,"view",Map.of()))
                .thenReturn(new AuthPermissionClient.Access(false,"Revoked"));
        mvc.perform(get("/api/metadata/query-audit/summary").header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(audit);
        verify(permissions,never()).check("alice","query_audit",null,"view",Map.of());
    }

    @Test void nakedRoleHeaderCannotReadAnySummary() throws Exception {
        mvc.perform(get("/api/metadata/query-audit/summary").header("X-User-Role","admin"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(permissions,audit);
    }
}
