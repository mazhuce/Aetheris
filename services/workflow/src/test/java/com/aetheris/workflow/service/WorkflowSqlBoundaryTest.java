package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowSqlBoundaryTest {
    @Test void retiredRawSqlBridgeCannotConstructOrCallTransport() {
        var builder=mock(RestClient.Builder.class,RETURNS_SELF);
        var client=new SqlExecutionClient(builder,"http://127.0.0.1:1",Duration.ofSeconds(1),"ignored");
        assertThrows(IllegalStateException.class,()->client.execute(1L,"INSERT INTO t SELECT 1",1));
        verifyNoInteractions(builder);
    }
}
