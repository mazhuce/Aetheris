package com.aetheris.governance.controller;

import com.aetheris.governance.entity.*;
import com.aetheris.governance.security.*;
import com.aetheris.governance.service.*;
import com.aetheris.governance.web.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GovernanceV2MvcTest {
    V2GovernanceFixture fixture;MockMvc mvc;
    final GovernancePolicyService policies=mock(GovernancePolicyService.class);
    final DataStandardService standards=mock(DataStandardService.class);
    @BeforeEach void setup() throws Exception {
        fixture=new V2GovernanceFixture();
        var policy=new GovernancePolicy();policy.setId(8L);policy.setName("original");policy.setPolicyType("masking");policy.setTargetTable("fixture.orders");policy.setRuleExpression("original");
        var standard=new DataStandard();standard.setId(8L);standard.setStandardCode("TEST");standard.setStandardName("Original");
        when(policies.getById(8L)).thenReturn(policy);when(policies.list()).thenReturn(List.of(policy));when(policies.updateById(any())).thenReturn(true);when(policies.removeById(8L)).thenReturn(true);
        when(standards.getById(8L)).thenReturn(standard);when(standards.updateById(any())).thenReturn(true);when(standards.removeById(8L)).thenReturn(true);
        var p=new GovernancePolicyController(policies);p.setAuthorization(fixture.authorization);
        var s=new DataStandardController(standards);s.setAuthorization(fixture.authorization);
        mvc=MockMvcBuilders.standaloneSetup(p,s).setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new GatewayIdentityInterceptor(fixture.authorization)).addFilters(fixture.workload(),fixture.direct()).build();
    }
    @AfterEach void cleanup() throws Exception {if(fixture!=null)fixture.close();}
    @Test void explicitResourceDenialRemains403RatherThanBeingChangedTo500() throws Exception {
        fixture.allowed.set(false);
        mvc.perform(get("/api/governance/policies/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isForbidden());
        mvc.perform(get("/api/governance/policies/-1").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isForbidden());
        verify(policies,never()).getById(any());
    }
    @Test void definitionNotFoundAndInvalidDtoKeepActual404And400WithProductionAdvice() throws Exception {
        when(policies.getById(8L)).thenReturn(null);
        mvc.perform(put("/api/governance/policies/8").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json")
                .content("{\"name\":\"rename\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/governance/policies").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json")
                .content("{\"name\":\"incomplete\"}")).andExpect(status().isBadRequest());
        verify(policies,never()).updateById(any());verify(policies,never()).save(any());
    }
    @Test void missingWrongPurposeAudienceUidVersionAndDisabledNeverReachBusiness() throws Exception {
        for(var entry:List.of(Map.entry("bad.proof.value",401),Map.entry(fixture.token("auth",42,3),401),Map.entry(fixture.token("governance",7,3),403),Map.entry(fixture.token("governance",42,2),403))){
            mvc.perform(get("/api/governance/policies/8").header("X-Aetheris-User-Proof",entry.getKey())).andExpect(status().is(entry.getValue()));
        }
        mvc.perform(get("/api/governance/policies/8").header("X-User-Name","alice").header("X-Internal-Token","legacy")).andExpect(status().isUnauthorized());
        fixture.active.set(false);mvc.perform(get("/api/governance/policies/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isForbidden());
        verifyNoInteractions(policies);
    }
    @Test void allInternalPathsAreClosedIncludingWellFormedV2() throws Exception {
        mvc.perform(post("/internal/permissions/check").header("X-Internal-Token","legacy")).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/v2/arbitrary").header("X-Aetheris-Workload-Proof","forged")).andExpect(status().isNotFound());
        verifyNoInteractions(policies,standards);
    }
    @Test void actualProofAllowsScopedReadDelegatedRenameAndExplicitDelete() throws Exception {
        mvc.perform(get("/api/governance/policies/8").header("X-Aetheris-User-Proof",fixture.token()).header("X-User-Name","admin")).andExpect(status().isOk());
        mvc.perform(put("/api/governance/policies/8").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json")
                .content("{\"name\":\"renamed\"}")).andExpect(status().isOk());
        verify(policies).updateById(argThat(p->p.getRuleExpression()==null&&p.getPolicyType()==null&&p.getName().equals("renamed")));
        mvc.perform(delete("/api/governance/standards/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isNoContent());
        verify(standards).removeById(8L);
    }
    @Test void fullCreateNeedsManageAndForgedDefinitionCannotBeSmuggledThroughUpdate() throws Exception {
        String create="{\"name\":\"Created\",\"policyType\":\"masking\",\"targetTable\":\"fixture.orders\"}";
        mvc.perform(post("/api/governance/policies").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json").content(create)).andExpect(status().isForbidden());
        verify(policies,never()).save(any());
        fixture.manage.set(true);
        mvc.perform(post("/api/governance/policies").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json").content(create)).andExpect(status().isOk());
        verify(policies).save(any());fixture.manage.set(false);
        mvc.perform(put("/api/governance/policies/8").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json")
                .content("{\"name\":\"renamed\",\"ruleExpression\":\"malicious\"}")).andExpect(status().isForbidden());
        verify(policies,never()).updateById(any());
    }
    @Test void revocationAndAuthUnavailableAreRecheckedOnEveryNewRequest() throws Exception {
        mvc.perform(get("/api/governance/policies/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isOk());
        fixture.allowed.set(false);mvc.perform(get("/api/governance/policies/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isForbidden());
        fixture.permissionFaultOnly=true;fixture.fault="unsigned";
        mvc.perform(get("/api/governance/policies/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isServiceUnavailable());
        verify(policies,times(1)).getById(8L);
    }
}
