package com.aetheris.auth.controller;

import com.aetheris.auth.service.PermissionDecisionService;
import com.aetheris.auth.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SelfPermissionControllerTest {
    private final PermissionDecisionService decisions=mock(PermissionDecisionService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new SelfPermissionController(decisions))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    @Test void onlyTheTrustedSubjectCanQueryItsOwnPermissionHints() throws Exception {
        when(decisions.allowedIds(eq("alice"),eq("ingestion_task"),eq("execute"),anyMap())).thenReturn(Set.of(1L,99L));
        mvc.perform(get("/api/auth/me/resource-permissions").requestAttr("aetheris.username","alice")
                .header("X-User-Name","admin").param("resourceType","ingestion_task").param("action","execute").param("ids","1,2"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.allowedIds.length()").value(1)).andExpect(jsonPath("$.allowedIds[0]").value(1));
        verify(decisions).allowedIds("alice","ingestion_task","execute",Map.of(1L,Map.of(),2L,Map.of()));
    }
    @Test void rejectsSubjectContextUnsupportedTypeAndUnboundedInput() throws Exception {
        for(String extra:java.util.List.of("username","ctx")) mvc.perform(get("/api/auth/me/resource-permissions")
                .requestAttr("aetheris.username","alice").param("resourceType","workflow_task").param("action","execute").param("ids","1").param(extra,"admin"))
                .andExpect(status().isBadRequest());
        for(String ids:java.util.List.of("0","-1","1,,2","9999999999999999999",String.join(",",java.util.Collections.nCopies(201,"1"))))
            mvc.perform(get("/api/auth/me/resource-permissions").requestAttr("aetheris.username","alice")
                    .param("resourceType","workflow_task").param("action","execute").param("ids",ids)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/me/resource-permissions").requestAttr("aetheris.username","alice")
                .param("resourceType","datasource").param("action","query").param("ids","1")).andExpect(status().isBadRequest());
        verifyNoInteractions(decisions);
    }
}
