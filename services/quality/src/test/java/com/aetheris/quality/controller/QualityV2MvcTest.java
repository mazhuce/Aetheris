package com.aetheris.quality.controller;

import com.aetheris.quality.entity.*;
import com.aetheris.quality.security.*;
import com.aetheris.quality.service.*;
import com.aetheris.quality.web.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual cryptographic filters + production MVC/advice, local signed auth and mock persistence. */
class QualityV2MvcTest {
    V2QualityFixture fixture;MockMvc mvc;
    final QualityRuleService rules=mock(QualityRuleService.class);
    final QualityCheckResultService results=mock(QualityCheckResultService.class);
    final QualityExecutionService execution=mock(QualityExecutionService.class);
    final QualitySchedulerService schedules=mock(QualitySchedulerService.class);
    final ExecutionRecoveryService recovery=mock(ExecutionRecoveryService.class);
    private static final String VALID="\"name\":\"renamed\",\"ruleType\":\"sql\",\"datasourceId\":1,\"targetTable\":\"fixture.orders\"";
    @BeforeEach void setup() throws Exception {
        fixture=new V2QualityFixture();var rule=new QualityRule();rule.setId(8L);rule.setName("original");rule.setRuleType("sql");rule.setDatasourceId(1L);rule.setTargetTable("fixture.orders");rule.setExpression("SELECT 0");rule.setStatus("enabled");
        when(rules.getById(8L)).thenReturn(rule);when(rules.list()).thenReturn(List.of(rule));when(rules.updateById(any())).thenReturn(true);when(rules.removeById(8L)).thenReturn(true);
        var controller=new QualityRuleController(rules,results,execution,schedules);controller.setAuthorization(fixture.authorization);
        var reconciler=new ExecutionRecoveryController(recovery,new RecoveryAdminGate(fixture.authorization));
        mvc=MockMvcBuilders.standaloneSetup(controller,reconciler).setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new GatewayIdentityInterceptor(fixture.authorization)).addFilters(fixture.workload(),fixture.direct()).build();
    }
    @AfterEach void cleanup() throws Exception{if(fixture!=null)fixture.close();}
    @Test void missingWrongAudienceUidVersionAndDisabledCannotReachBusiness() throws Exception {
        for(var pair:List.of(Map.entry("bad.proof",401),Map.entry(fixture.token("auth",42,3),401),Map.entry(fixture.token("quality",7,3),403),Map.entry(fixture.token("quality",42,2),403)))
            mvc.perform(get("/api/quality/rules/8").header("X-Aetheris-User-Proof",pair.getKey())).andExpect(status().is(pair.getValue()));
        mvc.perform(get("/api/quality/rules/8").header("X-Aetheris-Identity","legacy").header("X-User-Name","alice").header("X-User-Role","admin")).andExpect(status().isUnauthorized());
        fixture.active.set(false);mvc.perform(get("/api/quality/rules/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isForbidden());verifyNoInteractions(rules,execution,recovery);
    }
    @Test void exactReadScopeRevocationAndDependencyFailureRemain200403503() throws Exception {
        mvc.perform(get("/api/quality/rules/8").header("X-Aetheris-User-Proof",fixture.token()).header("X-User-Name","admin")).andExpect(status().isOk());
        fixture.allowed.set(false);mvc.perform(get("/api/quality/rules/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isForbidden());
        fixture.permissionFaultOnly=true;fixture.fault="unsigned";mvc.perform(get("/api/quality/rules/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isServiceUnavailable());
        verify(rules,times(1)).getById(8L);
    }
    @Test void delegatedRenameCannotChangeDefinitionAndExplicitDeleteRetainsItsPermission() throws Exception {
        mvc.perform(put("/api/quality/rules/8").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json").content("{"+VALID+"}")).andExpect(status().isOk());
        verify(rules).updateById(argThat(rule->rule.getExpression()==null&&rule.getRuleType()==null&&rule.getName().equals("renamed")));
        mvc.perform(put("/api/quality/rules/8").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json").content("{"+VALID+",\"expression\":\"SELECT 999\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/quality/rules/8").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isNoContent());verify(rules).removeById(8L);
    }
    @Test void createRequiresManageAndInvalidBodyAndMissingDefinitionRemain400404() throws Exception {
        mvc.perform(post("/api/quality/rules").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json").content("{"+VALID+"}")).andExpect(status().isForbidden());verify(rules,never()).save(any());
        fixture.manage.set(true);mvc.perform(post("/api/quality/rules").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json").content("{"+VALID+"}")).andExpect(status().isOk());verify(rules).save(any());
        mvc.perform(post("/api/quality/rules").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json").content("{\"name\":\"bad\"}")).andExpect(status().isBadRequest());
        fixture.manage.set(false);when(rules.getById(8L)).thenReturn(null);
        mvc.perform(put("/api/quality/rules/8").header("X-Aetheris-User-Proof",fixture.token()).contentType("application/json").content("{"+VALID+"}")).andExpect(status().isNotFound());
    }
    @Test void recoveryUsesCurrentSubjectAndExecuteIsNotImpliedByMutation() throws Exception {
        fixture.manage.set(false);mvc.perform(get("/api/quality/execution-recovery").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isForbidden());verifyNoInteractions(recovery);
        fixture.manage.set(true);mvc.perform(get("/api/quality/execution-recovery").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isOk());verify(recovery).pending(50);
        fixture.allowed.set(false);mvc.perform(post("/api/quality/rules/8/run").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isForbidden());verifyNoInteractions(execution);
    }
    @Test void manualRunPassesOnlyActualTypedSubjectAndPreservesUnknownAsBadGateway() throws Exception {
        var result=new QualityCheckResult();result.setId(11L);result.setRuleId(8L);result.setStatus("passed");
        when(execution.executeManual(eq(8L),any())).thenReturn(result);
        mvc.perform(post("/api/quality/rules/8/run").header("X-Aetheris-User-Proof",fixture.token()).header("X-User-Name","admin")).andExpect(status().isOk());
        var user=org.mockito.ArgumentCaptor.forClass(com.aetheris.security.servlet.TrustedUser.class);verify(execution).executeManual(eq(8L),user.capture());
        org.junit.jupiter.api.Assertions.assertEquals("alice",user.getValue().proof().username());org.junit.jupiter.api.Assertions.assertEquals("quality",user.getValue().proof().audience());
        when(execution.executeManual(eq(8L),any())).thenThrow(new QualityQueryClient.QualityExecutionException("固定质量结果待核实，禁止自动重放",true));
        mvc.perform(post("/api/quality/rules/8/run").header("X-Aetheris-User-Proof",fixture.token())).andExpect(status().isBadGateway());
        verify(execution,never()).execute(anyLong(),anyString());
    }
    @Test void everyInternalPathAndDuplicateUserHeaderAreClosed() throws Exception {
        mvc.perform(post("/internal/quality/rules/8/run").header("X-Internal-Token","legacy")).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/v2/executions/lookup").header("X-Aetheris-Workload-Proof","forged")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/quality/rules/8").header("X-Aetheris-User-Proof",fixture.token(),fixture.token())).andExpect(status().isUnauthorized());verifyNoInteractions(rules,execution);
    }
}
