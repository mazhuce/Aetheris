package com.aetheris.quality.controller;

import com.aetheris.quality.service.ExecutionRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionRecoveryControllerTest {
    @Test void bothReadingAndReleasingUnknownResultsRequirePlatformManagement() {
        var recovery=mock(ExecutionRecoveryService.class); var gate=mock(RecoveryAdminGate.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(gate).requireManage("viewer");
        var controller=new ExecutionRecoveryController(recovery,gate);
        assertThrows(ResponseStatusException.class,()->controller.pending(50,"viewer"));
        assertThrows(ResponseStatusException.class,()->controller.detail(1L,"viewer"));
        assertThrows(ResponseStatusException.class,()->controller.reconcile(1L,
                new ExecutionRecoveryController.Reconciliation("error",true,"verified remote query has stopped",null),"viewer"));
        verifyNoInteractions(recovery);
    }
}
