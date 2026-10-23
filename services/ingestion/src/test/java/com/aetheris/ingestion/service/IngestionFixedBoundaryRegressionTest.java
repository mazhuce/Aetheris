package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionTask;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class IngestionFixedBoundaryRegressionTest {
    @Test void bareUsernameCannotCreateAnExecutionInvocation() {
        var tasks=mock(IngestionTaskService.class);var runs=mock(IngestionRunService.class);var client=mock(MetadataIngestionClient.class);
        var task=task();when(tasks.getById(1L)).thenReturn(task);when(client.execute(task)).thenReturn(new MetadataIngestionClient.TransferResult(1,1,1));
        var service=new IngestionExecutionService(tasks,runs,client,mock(NotificationClient.class));
        assertThrows(IllegalStateException.class,()->service.execute(1L,"admin"));
        verifyNoInteractions(tasks,runs,client);
    }
    @Test void taskSqlCannotStillBeSubmittedThroughLegacyTokenClient() {
        var builder=RestClient.builder().baseUrl("http://metadata");var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://metadata/internal/ingestion/execute")).andRespond(withSuccess("{\"success\":true,\"rowsRead\":1,\"rowsWritten\":1,\"durationMs\":1}",MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class,()->new MetadataIngestionClient(builder.build()).execute(task()));
    }
    static IngestionTask task(){var t=new IngestionTask();t.setId(1L);t.setStatus("enabled");t.setSourceDatasourceId(1L);t.setTargetDatasourceId(2L);t.setSourceSql("SELECT 1");t.setTargetTable("target.t");return t;}
}
