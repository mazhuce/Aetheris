package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.ConnectionHealth;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.QueryHistory;
import com.aetheris.metadata.security.AetherisIdentity;
import com.aetheris.metadata.security.MetadataAccessExceptionHandler;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real Jackson/MVC boundaries; no driver, network, database or secret file is used. */
class DataSourceResponseBoundaryTest {
    private static final String BASE = "/api/metadata/datasources";
    private static final String SECRET = "credential-canary-9281";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DataSourceService service = mock(DataSourceService.class);
    private final JdbcService jdbc = mock(JdbcService.class);
    private final QueryHistoryService history = mock(QueryHistoryService.class);
    private final MetadataSecurity security = mock(MetadataSecurity.class);
    private final DataSourceHealthService health = mock(DataSourceHealthService.class);
    private MockMvc mvc;
    private DataSource source;

    @BeforeEach void setup() {
        source = source();
        when(service.getById(7L)).thenReturn(source);
        when(service.list()).thenReturn(List.of(source));
        when(service.save(any(DataSource.class))).thenReturn(true);
        when(service.updateById(any(DataSource.class))).thenReturn(true);
        when(security.requireIdentity(any())).thenReturn(new AetherisIdentity(4L, "alice"));
        when(security.require(any(), eq("datasource"), nullable(Long.class), anyString(), anyMap()))
                .thenReturn(new AetherisIdentity(4L, "alice"));
        when(security.allowedIds(eq("alice"), eq("datasource"), eq("view"), anyMap())).thenReturn(Set.of(7L));
        when(health.attach(any(DataSource.class))).thenAnswer(call -> call.getArgument(0));
        when(health.attach(anyList())).thenAnswer(call -> call.getArgument(0));
        mvc = MockMvcBuilders.standaloneSetup(new DataSourceController(service, jdbc, history, security, health))
                .setControllerAdvice(new MetadataAccessExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    @Test void listAndDetailNeverEchoHistoricEmbeddedCredentialsOrInternalFields() throws Exception {
        source.setJdbcUrl("jdbc:postgresql://reader:" + SECRET + "@db.example/business");
        source.setConnectionParams("password=" + SECRET);
        for (String path : List.of(BASE, BASE + "/7")) {
            var result = mvc.perform(get(path)).andReturn();
            assertEquals(200, result.getResponse().getStatus());
            String body = result.getResponse().getContentAsString();
            assertFalse(body.contains(SECRET), body);
            JsonNode dto = json.readTree(body); if (dto.isArray()) dto = dto.get(0);
            assertTrue(dto.path("connectionConfigurationRedacted").asBoolean());
            for (String key : List.of("password", "writeUsername", "writePassword", "disableWriteCredentials", "connectionRevision"))
                assertFalse(dto.has(key), key);
        }
        assertTrue(source.getJdbcUrl().contains(SECRET), "response mapping must not mutate the JDBC entity");
        assertEquals("password=" + SECRET, source.getConnectionParams());
    }

    @Test void safeConnectionFieldsAndWriteConfiguredRemainEditableWithoutWriterIdentity() throws Exception {
        source.setJdbcUrl("jdbc:postgresql://db.example:5432/business?sslmode=verify-full");
        source.setConnectionParams("connectTimeout=5&ApplicationName=aetheris");
        var response = mvc.perform(get(BASE + "/7")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        JsonNode dto = json.readTree(response.getContentAsString());
        assertEquals(source.getJdbcUrl(), dto.path("jdbcUrl").asText());
        assertEquals(source.getConnectionParams(), dto.path("connectionParams").asText());
        assertEquals("reader", dto.path("username").asText());
        assertTrue(dto.path("writeConfigured").asBoolean());
        assertFalse(dto.path("connectionConfigurationRedacted").asBoolean());
        assertFalse(dto.has("writeUsername")); assertFalse(dto.has("writePassword"));
        assertEquals("unknown", dto.path("connectionHealth").path("status").asText());
        Set<String> fields = new java.util.HashSet<>(); dto.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "name", "type", "host", "port", "databaseName", "username", "writeConfigured", "managed",
                "jdbcUrl", "connectionParams", "description", "status", "connectionHealth", "createdAt", "updatedAt",
                "connectionConfigurationRedacted"), fields);
    }

    @Test void historicProbeFailureTextIsNotAResponseField() throws Exception {
        source.setConnectionHealth(new ConnectionHealth("unavailable", null, null, null, 2L,
                "AUTHENTICATION_FAILED", "jdbc:postgresql://reader:" + SECRET + "@db.example/business"));
        String body = mvc.perform(get(BASE + "/7")).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(SECRET), body);
        assertTrue(body.contains("AUTHENTICATION_FAILED"));
    }

    @ParameterizedTest @ValueSource(strings = {"host", "network-database", "h2", "sqlite", "derby"})
    void historicStructuredConnectionFieldsCannotLeakRecognizableSecrets(String field) throws Exception {
        if (field.equals("host")) source.setHost("reader:" + SECRET + "@db.example");
        else if (field.equals("network-database")) source.setDatabaseName("business;password=" + SECRET);
        else {
            source.setType(field); source.setHost("embedded");
            source.setDatabaseName((field.equals("sqlite") ? "fixture.db" : "mem:fixture") + ";PASSWORD=" + SECRET);
        }
        var response = mvc.perform(get(BASE + "/7")).andReturn().getResponse();
        assertEquals(200, response.getStatus()); String body = response.getContentAsString();
        assertFalse(body.contains(SECRET), body);
        JsonNode dto = json.readTree(body); assertTrue(dto.path("connectionConfigurationRedacted").asBoolean());
        assertTrue(dto.path(field.equals("host") ? "host" : "databaseName").isNull());
        assertEquals("reader", dto.path("username").asText());
        assertTrue((field.equals("host") ? source.getHost() : source.getDatabaseName()).contains(SECRET));
    }

    @Test void savedAndUnsavedProbeResponsesWhitelistHealthAndDoNotEchoUnknownFields() throws Exception {
        var raw = new java.util.LinkedHashMap<String,Object>();
        raw.put("success", false); raw.put("message", SECRET); raw.put("password", SECRET); raw.put("jdbcUrl", SECRET);
        raw.put("failureCode", "AUTHENTICATION_FAILED");
        raw.put("connectionHealth", new ConnectionHealth("unavailable", null, null, null, 2L, "AUTHENTICATION_FAILED", SECRET));
        when(health.test(any())).thenReturn(raw); when(health.testBeforeSave(any())).thenReturn(raw);
        for (boolean saved : List.of(false, true)) {
            var request = post(saved ? BASE + "/7/test" : BASE + "/test");
            if (!saved) request.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"business\",\"type\":\"postgresql\",\"host\":\"db.example\",\"status\":\"active\"}");
            var response = mvc.perform(request).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            assertFalse(response.getContentAsString().contains(SECRET));
            JsonNode result = json.readTree(response.getContentAsString());
            assertFalse(result.has("password")); assertFalse(result.has("jdbcUrl"));
            assertFalse(result.path("success").asBoolean());
        }
    }

    @Test void invalidUnsavedConfigurationNeverReachesProbe() throws Exception {
        var response = mvc.perform(post(BASE + "/test").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "business", "type", "postgresql", "host", "db.example",
                        "connectionParams", "password=" + SECRET)))).andReturn().getResponse();
        assertEquals(400, response.getStatus()); assertFalse(response.getContentAsString().contains(SECRET));
        verify(health, never()).testBeforeSave(any());
    }

    @ParameterizedTest @ValueSource(strings = {"url", "params", "unknown-key"})
    void createAndUpdateRejectConnectionCredentialsBeforePersistence(String field) throws Exception {
        Map<String,Object> input = new java.util.LinkedHashMap<>(Map.of("name", "business", "type", "postgresql",
                "host", "db.example", "port", 5432, "databaseName", "business", "status", "active"));
        if (field.equals("url")) input.put("jdbcUrl", "jdbc:postgresql://reader:" + SECRET + "@db.example/business");
        else input.put("connectionParams", (field.equals("params") ? "password=" : SECRET + "=") + SECRET);
        for (boolean update : List.of(false, true)) {
            var builder = update ? put(BASE + "/7") : post(BASE);
            var response = mvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input)))
                    .andReturn().getResponse();
            assertEquals(400, response.getStatus());
            assertFalse(response.getContentAsString().contains(SECRET));
        }
        verify(service, never()).save(any(DataSource.class));
        verify(service, never()).updateById(any(DataSource.class));
    }

    @Test void createAndUpdateAlsoUseTheResponseWhitelist() throws Exception {
        String input = json.writeValueAsString(Map.of("name", "business", "type", "postgresql", "host", "db.example",
                "status", "active", "username", "reader", "password", SECRET, "writeUsername", "writer", "writePassword", SECRET));
        for (boolean update : List.of(false, true)) {
            var builder = update ? put(BASE + "/7") : post(BASE);
            var response = mvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(input)).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            JsonNode dto = json.readTree(response.getContentAsString());
            assertTrue(dto.path("writeConfigured").asBoolean());
            assertFalse(dto.has("writeUsername")); assertFalse(dto.has("writePassword"));
            assertFalse(dto.has("password")); assertFalse(dto.has("connectionRevision"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"query", "schemas", "tables", "columns", "preview"})
    void jdbcFailuresDoNotExposeDriverTextOrStoreItInHistory(String operation) throws Exception {
        var error = new IllegalStateException("jdbc:postgresql://reader:" + SECRET + "@db.example/business password=" + SECRET);
        when(jdbc.query(any(), anyString())).thenThrow(error);
        when(jdbc.listSchemas(any())).thenThrow(error);
        when(jdbc.listTables(any(), anyString())).thenThrow(error);
        when(jdbc.listColumns(any(), anyString(), anyString())).thenThrow(error);
        when(jdbc.preview(any(), anyString(), anyString())).thenThrow(error);
        var builder = switch(operation) {
            case "query" -> post(BASE + "/7/query").contentType(MediaType.APPLICATION_JSON).content("{\"sql\":\"SELECT 1\"}");
            case "preview" -> post(BASE + "/7/preview").contentType(MediaType.APPLICATION_JSON).content("{\"schema\":\"public\",\"table\":\"orders\"}");
            default -> get(BASE + "/7/" + operation).param("schema", "public").param("table", "orders");
        };
        var response = mvc.perform(builder).andReturn().getResponse();
        assertEquals(502, response.getStatus());
        assertFalse(response.getContentAsString().contains(SECRET));
        if (operation.equals("query")) {
            var record = ArgumentCaptor.forClass(QueryHistory.class); verify(history).record(record.capture());
            assertFalse(record.getValue().getErrorMessage().contains(SECRET));
        }
    }

    @Test void jdbcFailureAndHistoryFailureAreNotLoggedWithDriverMessagesOrStackTraces() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DataSourceController.class);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start(); logger.addAppender(capture);
        try {
            when(jdbc.query(any(), anyString())).thenThrow(new IllegalStateException("password=" + SECRET));
            doThrow(new IllegalStateException("password=" + SECRET)).when(history).record(any());
            assertEquals(502, mvc.perform(post(BASE + "/7/query").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sql\":\"SELECT 1\"}")).andReturn().getResponse().getStatus());
            assertEquals(2, capture.list.size());
            for (var event : capture.list) {
                assertFalse(event.getFormattedMessage().contains(SECRET)); assertNull(event.getThrowableProxy());
            }
        } finally { logger.detachAppender(capture); capture.stop(); }
    }

    @Test void connectionValidationDoesNotFlattenAuthFailures() throws Exception {
        when(security.require(any(), eq("datasource"), eq(7L), eq("view"), anyMap()))
                .thenThrow(new MetadataSecurity.PermissionServiceUnavailableException("permission unavailable"));
        assertEquals(503, mvc.perform(get(BASE + "/7")).andReturn().getResponse().getStatus());
        when(security.require(any(), eq("datasource"), eq(7L), eq("view"), anyMap()))
                .thenThrow(new MetadataSecurity.ForbiddenException("permission denied"));
        assertEquals(403, mvc.perform(get(BASE + "/7")).andReturn().getResponse().getStatus());
    }

    private static DataSource source() {
        var source = new DataSource(); source.setId(7L); source.setName("business"); source.setType("postgresql");
        source.setHost("db.example"); source.setPort(5432); source.setDatabaseName("business"); source.setStatus("active");
        source.setUsername("reader"); source.setPassword(SECRET); source.setWriteUsername("writer"); source.setWritePassword(SECRET);
        source.setConnectionRevision(42L); source.setManaged(false); return source;
    }
}
