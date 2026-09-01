package com.aetheris.governance.controller;

import com.aetheris.governance.entity.GovernancePolicy;
import com.aetheris.governance.service.GovernancePolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernancePolicyControllerTest {

    private final GovernancePolicyService service = mock(GovernancePolicyService.class);
    private final GovernancePolicyController controller = new GovernancePolicyController(service);

    private GovernancePolicy policy(Long id, String name) {
        GovernancePolicy policy = new GovernancePolicy();
        policy.setId(id);
        policy.setName(name);
        policy.setPolicyType("access");
        policy.setTargetTable("dwd.orders");
        policy.setStatus("active");
        return policy;
    }

    @Test
    void listReturnsAllPolicies() {
        when(service.list()).thenReturn(List.of(policy(1L, "敏感字段脱敏")));
        List<GovernancePolicy> result = controller.list();
        assertEquals(1, result.size());
        assertEquals("敏感字段脱敏", result.get(0).getName());
    }

    @Test
    void getByIdReturnsPolicy() {
        when(service.getById(1L)).thenReturn(policy(1L, "敏感字段脱敏"));
        ResponseEntity<GovernancePolicy> response = controller.getById(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("敏感字段脱敏", response.getBody().getName());
    }

    @Test
    void getByIdReturns404WhenMissing() {
        when(service.getById(99L)).thenReturn(null);
        assertEquals(HttpStatus.NOT_FOUND, controller.getById(99L).getStatusCode());
    }

    @Test
    void createSavesAndReturnsPolicy() {
        GovernancePolicy input = policy(null, "表级访问控制");
        when(service.save(input)).thenReturn(true);
        assertEquals(input, controller.create(input));
        ArgumentCaptor<GovernancePolicy> captor = ArgumentCaptor.forClass(GovernancePolicy.class);
        verify(service).save(captor.capture());
        assertEquals("表级访问控制", captor.getValue().getName());
    }

    @Test
    void updateAppliesPathId() {
        GovernancePolicy input = policy(null, "更新后的策略");
        when(service.updateById(input)).thenReturn(true);
        when(service.getById(4L)).thenReturn(policy(4L, "更新后的策略"));

        ResponseEntity<GovernancePolicy> response = controller.update(4L, input);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(4L, input.getId());
        verify(service).updateById(input);
    }

    @Test
    void updateReturns404WhenNothingChanged() {
        when(service.updateById(any(GovernancePolicy.class))).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND, controller.update(99L, policy(null, "x")).getStatusCode());
    }

    @Test
    void deleteReturns204WhenRemoved() {
        when(service.removeById(1L)).thenReturn(true);
        assertEquals(HttpStatus.NO_CONTENT, controller.delete(1L).getStatusCode());
    }

    @Test
    void deleteReturns404WhenMissing() {
        when(service.removeById(99L)).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND, controller.delete(99L).getStatusCode());
    }
}
