package com.aetheris.workflow.service;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.core.ParameterizedTypeReference;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit RED-only reproduction against the archived HEAD method, excluded from ordinary *Test discovery. */
class WorkflowSqlLegacyRed {
    @Test @SuppressWarnings("unchecked") void rawSqlWithoutApprovedSourceMustNotExecute(){
        var builder=mock(RestClient.Builder.class,RETURNS_SELF);var rest=mock(RestClient.class);
        var request=mock(RestClient.RequestBodyUriSpec.class,RETURNS_SELF);var response=mock(RestClient.ResponseSpec.class);
        when(builder.build()).thenReturn(rest);when(rest.mutate()).thenReturn(builder);when(rest.post()).thenReturn(request);
        when(request.uri(anyString(),any(Object[].class))).thenReturn(request);when(request.retrieve()).thenReturn(response);
        when(response.body(any(ParameterizedTypeReference.class))).thenReturn(Map.of("success",true,"affectedRows",1,"durationMs",1));
        var legacy=new LegacySqlExecutionClient(builder,"http://127.0.0.1:1",Duration.ofSeconds(1),"fixture");
        assertThrows(IllegalStateException.class,()->legacy.execute(8L,"INSERT INTO t SELECT 1",1));
    }
}
