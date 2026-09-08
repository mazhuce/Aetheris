package com.aetheris.governance.controller;

import com.aetheris.governance.entity.DataStandard;
import com.aetheris.governance.service.DataStandardService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataStandardControllerTest {

    private final DataStandardService service = mock(DataStandardService.class);
    private final DataStandardController controller = new DataStandardController(service);

    private DataStandard standard(Long id, String code, String name) {
        DataStandard standard = new DataStandard();
        standard.setId(id);
        standard.setStandardCode(code);
        standard.setStandardName(name);
        standard.setStandardType("naming");
        standard.setStatus("draft");
        return standard;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsAllStandards() {
        when(service.list(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(standard(1L, "DS-NAME-001", "字段命名规范-下划线小写")));
        List<DataStandard> result = controller.list(null, null, null);
        assertEquals(1, result.size());
        assertEquals("DS-NAME-001", result.get(0).getStandardCode());
    }

    @Test
    void getByIdReturnsStandard() {
        when(service.getById(1L)).thenReturn(standard(1L, "DS-NAME-001", "字段命名规范-下划线小写"));
        ResponseEntity<DataStandard> response = controller.getById(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("DS-NAME-001", response.getBody().getStandardCode());
    }

    @Test
    void getByIdReturns404WhenMissing() {
        when(service.getById(99L)).thenReturn(null);
        assertEquals(HttpStatus.NOT_FOUND, controller.getById(99L).getStatusCode());
    }

    @Test
    void createSavesWithCreatedByFromHeader() {
        DataStandard input = standard(null, "DS-ENC-002", "币种编码标准");
        when(service.save(input)).thenReturn(true);
        ResponseEntity<?> response = controller.create(input, "zhangsan");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<DataStandard> captor = ArgumentCaptor.forClass(DataStandard.class);
        verify(service).save(captor.capture());
        assertEquals("zhangsan", captor.getValue().getCreatedBy());
    }

    @Test
    void createReturns409OnDuplicateCode() {
        when(service.save(any(DataStandard.class))).thenThrow(new DuplicateKeyException("duplicate"));
        ResponseEntity<?> response =
                controller.create(standard(null, "DS-NAME-001", "字段命名规范-下划线小写"), "zhangsan");
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void updateAppliesPathIdAndKeepsCreatedBy() {
        DataStandard input = standard(null, "DS-VAL-001", "订单状态取值标准");
        input.setCreatedBy("attacker");
        when(service.updateById(input)).thenReturn(true);
        when(service.getById(4L)).thenReturn(standard(4L, "DS-VAL-001", "订单状态取值标准"));

        ResponseEntity<?> response = controller.update(4L, input);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(4L, input.getId());
        assertEquals(null, input.getCreatedBy());
        verify(service).updateById(input);
    }

    @Test
    void updateReturns404WhenNothingChanged() {
        when(service.updateById(any(DataStandard.class))).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND,
                controller.update(99L, standard(null, "DS-VAL-001", "订单状态取值标准")).getStatusCode());
    }

    @Test
    void updateReturns409OnDuplicateCode() {
        when(service.updateById(any(DataStandard.class))).thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(HttpStatus.CONFLICT,
                controller.update(4L, standard(null, "DS-NAME-001", "字段命名规范-下划线小写")).getStatusCode());
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
