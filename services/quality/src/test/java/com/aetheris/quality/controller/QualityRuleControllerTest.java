package com.aetheris.quality.controller;

import com.aetheris.quality.entity.QualityRule;
import com.aetheris.quality.service.QualityCheckResultService;
import com.aetheris.quality.service.QualityExecutionService;
import com.aetheris.quality.service.QualityRuleService;
import com.aetheris.quality.service.QualitySchedulerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityRuleControllerTest {

    private final QualityRuleService service = mock(QualityRuleService.class);
    private final QualityRuleController controller = new QualityRuleController(
            service, mock(QualityCheckResultService.class), mock(QualityExecutionService.class),
            mock(QualitySchedulerService.class));

    private QualityRule rule(Long id, String name) {
        QualityRule rule = new QualityRule();
        rule.setId(id);
        rule.setName(name);
        rule.setRuleType("not_null");
        rule.setDatasourceId(1L);
        rule.setTargetTable("ods.orders");
        rule.setStatus("active");
        return rule;
    }

    @Test
    void listReturnsAllRules() {
        when(service.list()).thenReturn(List.of(rule(1L, "非空校验")));
        assertEquals(1, controller.list().size());
        assertEquals("非空校验", controller.list().get(0).getName());
    }

    @Test
    void getByIdReturnsRule() {
        when(service.getById(1L)).thenReturn(rule(1L, "非空校验"));
        ResponseEntity<QualityRule> response = controller.getById(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("非空校验", response.getBody().getName());
    }

    @Test
    void getByIdReturns404WhenMissing() {
        when(service.getById(99L)).thenReturn(null);
        assertEquals(HttpStatus.NOT_FOUND, controller.getById(99L).getStatusCode());
    }

    @Test
    void createSavesAndReturnsRule() {
        QualityRule input = rule(null, "表行数检查");
        when(service.save(input)).thenReturn(true);

        QualityRule result = controller.create(input);

        assertEquals(input, result);
        ArgumentCaptor<QualityRule> captor = ArgumentCaptor.forClass(QualityRule.class);
        verify(service).save(captor.capture());
        assertEquals("表行数检查", captor.getValue().getName());
    }

    @Test
    void updateAppliesPathIdAndReturnsUpdatedRule() {
        QualityRule input = rule(null, "更新后的规则");
        when(service.updateById(input)).thenReturn(true);
        when(service.getById(3L)).thenReturn(rule(3L, "更新后的规则"));

        ResponseEntity<QualityRule> response = controller.update(3L, input);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3L, input.getId());
        assertEquals("更新后的规则", response.getBody().getName());
    }

    @Test
    void updateReturns404WhenNothingChanged() {
        when(service.updateById(org.mockito.ArgumentMatchers.any(QualityRule.class))).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND, controller.update(99L, rule(null, "x")).getStatusCode());
    }

    @Test
    void deleteReturns204WhenRemoved() {
        when(service.removeById(1L)).thenReturn(true);
        ResponseEntity<Void> response = controller.delete(1L);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void deleteReturns404WhenMissing() {
        when(service.removeById(99L)).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND, controller.delete(99L).getStatusCode());
    }
}
