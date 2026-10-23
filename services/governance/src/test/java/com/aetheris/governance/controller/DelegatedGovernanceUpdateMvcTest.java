package com.aetheris.governance.controller;

import com.aetheris.governance.entity.DataStandard;
import com.aetheris.governance.entity.GovernancePolicy;
import com.aetheris.governance.security.ResourceAuthorization;
import com.aetheris.governance.service.DataStandardService;
import com.aetheris.governance.service.GovernancePolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercise MVC validation as well as the real protected-field guard. */
class DelegatedGovernanceUpdateMvcTest {
    private final GovernancePolicyService policies=mock(GovernancePolicyService.class);
    private final DataStandardService standards=mock(DataStandardService.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        ResourceAuthorization delegated=new ResourceAuthorization() {
            @Override public boolean allowed(String type,Long id,String action) { return Long.valueOf(1).equals(id); }
            @Override public boolean mayManage() { return false; }
        };
        GovernancePolicy policy=new GovernancePolicy(); policy.setId(1L); policy.setName("Original");
        policy.setPolicyType("masking"); policy.setTargetTable("warehouse.orders"); policy.setRuleExpression("phone");
        DataStandard standard=new DataStandard(); standard.setId(1L); standard.setStandardCode("ORDER");
        standard.setStandardName("Original"); standard.setRuleExpression("stable");
        when(policies.getById(1L)).thenReturn(policy); when(policies.updateById(any())).thenReturn(true);
        when(standards.getById(1L)).thenReturn(standard); when(standards.updateById(any())).thenReturn(true);
        GovernancePolicyController p=new GovernancePolicyController(policies); p.setAuthorization(delegated);
        DataStandardController s=new DataStandardController(standards); s.setAuthorization(delegated);
        mvc=MockMvcBuilders.standaloneSetup(p,s).build();
    }
    @Test void safePolicyUpdateDoesNotRequireOrWriteExecutionFields() throws Exception {
        mvc.perform(put("/api/governance/policies/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"description\":\"Explanation\"}" )).andExpect(status().isOk());
        verify(policies).updateById(argThat(value -> value.getPolicyType()==null && value.getTargetTable()==null
                && value.getRuleExpression()==null && "Renamed".equals(value.getName())));
    }
    @Test void safeStandardUpdateDoesNotRequireOrWriteCodeOrRules() throws Exception {
        mvc.perform(put("/api/governance/standards/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"standardName\":\"Renamed\",\"status\":\"draft\"}" )).andExpect(status().isOk());
        verify(standards).updateById(argThat(value -> value.getStandardCode()==null && value.getRuleExpression()==null
                && "Renamed".equals(value.getStandardName())));
    }
    @Test void changedDefinitionAndNeighbourRemainForbidden() throws Exception {
        mvc.perform(put("/api/governance/policies/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"policyType\":\"access\",\"targetTable\":\"secret.t\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/governance/standards/2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"standardName\":\"Renamed\",\"standardCode\":\"X\"}" )).andExpect(status().isForbidden());
        verify(policies,never()).updateById(any()); verify(standards,never()).updateById(any());
    }
    @Test void creationStillRequiresFullDefinition() throws Exception {
        mvc.perform(post("/api/governance/policies").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Incomplete\"}" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/governance/standards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"standardName\":\"Incomplete\"}" )).andExpect(status().isBadRequest());
        verify(policies,never()).save(any()); verify(standards,never()).save(any());
    }
}
