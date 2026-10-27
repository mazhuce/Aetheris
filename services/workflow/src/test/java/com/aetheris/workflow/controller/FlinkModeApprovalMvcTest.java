package com.aetheris.workflow.controller;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.fixedexecution.FixedJson;
import com.aetheris.workflow.fixedexecution.WorkflowEngineMaterial;
import com.aetheris.workflow.fixedexecution.WorkflowExecutionStore;
import com.aetheris.workflow.security.GatewayIdentityInterceptor;
import com.aetheris.workflow.security.V2WorkflowFixture;
import com.aetheris.workflow.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Exercises public create/approve controllers, the real store and actual material capture.
 * Only persistence/projection and the auth peer are fixtures; this is not PostgreSQL acceptance. */
class FlinkModeApprovalMvcTest {
    @Test void publicBoundedTaskCanBeCreatedAndApprovedWithoutChangingItsMode() throws Exception {
        try (var h = new Harness()) {
            h.create("bounded", 200);
            assertEquals("bounded", h.saved.getFlinkMode());
            var approved = h.approve(200);
            assertTrue(approved.path("approved").booleanValue());
            assertEquals("bounded", h.engine.path("mode").asText());
            assertEquals("bounded", FixedJson.parse(h.definition().canonicalDefinition()).path("flinkMode").asText());
            assertEquals(42, FixedJson.parse(h.approval).path("uid").asLong());
            assertEquals(3, FixedJson.parse(h.approval).path("accessVersion").asLong());
            assertEquals(h.engine, h.store.executableDefinition(h.definition()).get("engineDefinition"));
            verify(h.scheduler, times(2)).sync(h.saved);
        }
    }

    @Test void publicStreamingTaskRetainsTheExistingCreateAndApprovalContract() throws Exception {
        try (var h = new Harness()) {
            h.create("streaming", 200);
            h.approve(200);
            assertEquals("streaming", h.saved.getFlinkMode());
            assertEquals("streaming", h.engine.path("mode").asText());
        }
    }

    @Test void legacyInternalBatchNameCannotBeSubmittedThroughThePublicCreateApi() throws Exception {
        try (var h = new Harness()) {
            h.create("batch", 400);
            assertNull(h.saved);
            verify(h.tasks, never()).save(any());
            verifyNoInteractions(h.jdbc);
        }
    }

    @Test void legacyBatchMaterialCanBeReadWithoutRewritingItsBytesOrDigest() {
        String canonical = "{\"mode\": \"batch\", \"engine\": \"flink\", \"schemaVersion\": 1, "
                + "\"gatewayOrigin\": \"http://127.0.0.1:19010\", \"jobManagerOrigin\": \"http://127.0.0.1:19011\", \"queueTimeoutSeconds\": 0}";
        String digest = WorkflowEngineMaterial.digest(canonical);
        var legacy = FixedJson.parse(canonical);
        String originalNode = legacy.toString();
        WorkflowEngineMaterial.validate(legacy, "flink");
        assertEquals("batch", legacy.path("mode").asText());
        assertEquals(originalNode, legacy.toString());
        assertEquals(digest, WorkflowEngineMaterial.digest(canonical));
        // An existing receipt binds the exact legacy material hash, not a normalized copy.
        var receipt = FixedJson.JSON.createObjectNode().put("engineDefinitionSha256", digest);
        assertEquals(digest, receipt.path("engineDefinitionSha256").asText());
        assertNotEquals(digest, WorkflowEngineMaterial.digest(canonical.replace("batch", "bounded")));
    }

    @Test void legacyBatchMaterialIsNotReusedAsCurrentBoundedDispatchAuthority() throws Exception {
        try (var h = new Harness()) {
            h.create("bounded", 200);
            h.engine = FixedJson.JSON.createObjectNode().put("schemaVersion", 1).put("engine", "flink")
                    .put("gatewayOrigin", "http://127.0.0.1:19010").put("jobManagerOrigin", "http://127.0.0.1:19011")
                    .put("mode", "batch").put("queueTimeoutSeconds", 0);
            String before = h.engine.toString();
            WorkflowEngineMaterial.validate(h.engine, "flink");
            assertThrows(IllegalStateException.class, () -> h.material.requireCurrent(h.definition()));
            assertEquals(before, h.engine.toString());
            assertNull(h.approval);
        }
    }

    @Test void batchInAnOldMutableTaskStillRequiresExplicitPublicModeAndReapproval() throws Exception {
        try (var h = new Harness()) {
            h.create("bounded", 200);
            h.saved.setFlinkMode("batch");
            assertThrows(IllegalArgumentException.class, () -> h.material.current(h.definition()));
            assertNull(h.engine);
            assertNull(h.approval);
        }
    }

    private static final class Harness implements AutoCloseable {
        private static final long TASK_ID = 8L;
        final V2WorkflowFixture user = new V2WorkflowFixture();
        final TaskService tasks = mock(TaskService.class);
        final TaskSchedulerService scheduler = mock(TaskSchedulerService.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final WorkflowEngineMaterial material;
        final WorkflowExecutionStore store;
        final MockMvc mvc;
        Task saved;
        ObjectNode engine;
        String approval;
        long revision = 1;

        Harness() throws Exception {
            user.manage.set(true);
            var transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
            var catalog = mock(ManagedSparkCatalogService.class);
            material = new WorkflowEngineMaterial(jdbc, transactions, catalog, new OutboundTargetPolicy(),
                    new MockEnvironment().withProperty("aetheris.flink-jobmanager.base-url", "http://127.0.0.1:19011"));
            store = new WorkflowExecutionStore(jdbc, transactions, mock(ExecutionClaimCoordinator.class), tasks);
            store.installEngineMaterial(material);

            when(tasks.save(any(Task.class))).thenAnswer(call -> {
                saved = call.getArgument(0); saved.setId(TASK_ID); return true;
            });
            when(tasks.getById(TASK_ID)).thenAnswer(ignored -> saved);
            when(jdbc.query(contains("FROM workflow.task WHERE id=?"),
                    org.mockito.ArgumentMatchers.<RowMapper<WorkflowExecutionStore.Definition>>any(), eq(TASK_ID)))
                    .thenAnswer(ignored -> List.of(definition()));
            when(jdbc.queryForObject(eq("SELECT (?::jsonb)::text"), eq(String.class), anyString()))
                    .thenAnswer(call -> call.getArgument(2, String.class));
            when(jdbc.update(eq("UPDATE workflow.task SET engine_definition_json=?::jsonb WHERE id=?"), anyString(), eq(TASK_ID)))
                    .thenAnswer(call -> {
                        engine = (ObjectNode) FixedJson.parse(call.getArgument(1, String.class));
                        revision++; return 1;
                    });
            when(jdbc.update(contains("SET execution_approval=jsonb_build_object"),
                    anyLong(), anyString(), anyLong(), eq(TASK_ID), anyLong(), anyString()))
                    .thenAnswer(call -> {
                        approval = FixedJson.JSON.createObjectNode().put("uid", call.getArgument(1, Long.class))
                                .put("username", call.getArgument(2, String.class)).put("accessVersion", call.getArgument(3, Long.class))
                                .put("revision", call.getArgument(5, Long.class)).put("definitionSha256", call.getArgument(6, String.class)).toString();
                        return 1;
                    });

            var client = mock(SqlExecutionClient.class);
            var controller = new TaskController(tasks, mock(TaskInstanceService.class), mock(TaskLogService.class),
                    mock(ExecutionAttemptService.class), mock(ExecutionResolutionService.class), mock(TaskDependencyService.class),
                    mock(TaskExecutionService.class), scheduler, mock(SchedulerSyncRecoveryService.class), client,
                    catalog, mock(SparkBatchExecutionService.class), mock(WorkflowAuthorizationService.class));
            controller.setAuthorization(user.authorization);
            var approver = new WorkflowApprovalController(store, user.authorization, client, scheduler, tasks);
            mvc = MockMvcBuilders.standaloneSetup(controller, approver)
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .setMessageConverters(new ByteArrayHttpMessageConverter(),
                            new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                    .addMappedInterceptors(new String[]{"/api/**"}, new GatewayIdentityInterceptor(user.authorization))
                    .addFilters(user.workload(), user.direct()).build();
        }

        void create(String mode, int status) throws Exception {
            var body = FixedJson.JSON.createObjectNode().put("name", "public-flink-mode-contract")
                    .put("taskType", "flink").put("taskSql", "INSERT INTO sink SELECT 1")
                    .put("flinkEndpoint", "http://127.0.0.1:19010").put("flinkMode", mode)
                    .put("status", "enabled").put("timeoutSeconds", 60).put("maxRetries", 0);
            var response = mvc.perform(post("/api/workflow/tasks").header(SignedHttpClient.USER_PROOF, user.token())
                    .contentType("application/json").content(body.toString())).andReturn().getResponse();
            assertEquals(status, response.getStatus(), response.getContentAsString());
            if (status == 200) assertEquals(mode, FixedJson.parse(response.getContentAsByteArray(), 65536).at("/task/flinkMode").asText());
        }

        JsonNode approve(int status) throws Exception {
            var response = mvc.perform(post("/api/workflow/tasks/8/approve").header(SignedHttpClient.USER_PROOF, user.token())
                    .contentType("application/json").content("{\"expectedRevision\":" + revision + "}"))
                    .andReturn().getResponse();
            assertEquals(status, response.getStatus(), response.getContentAsString());
            return FixedJson.parse(response.getContentAsByteArray(), 65536);
        }

        WorkflowExecutionStore.Definition definition() {
            var n = FixedJson.JSON.createObjectNode().put("schemaVersion", 1).put("taskType", "flink")
                    .put("sql", saved.getTaskSql()).put("timeoutSeconds", saved.getTimeoutSeconds())
                    .put("maxRetries", saved.getMaxRetries()).put("flinkEndpoint", saved.getFlinkEndpoint()).put("flinkMode", saved.getFlinkMode());
            for (String field : List.of("datasourceId", "targetTable", "jobDefinitionId", "qualityRuleId", "eventTimeColumn",
                    "watermarkExpression", "allowedLatenessSeconds", "lateDataPolicy", "deadLetterTable")) n.putNull(field);
            n.set("dependencies", FixedJson.JSON.createObjectNode());
            if (engine == null) n.putNull("engineDefinition"); else n.set("engineDefinition", engine.deepCopy());
            String canonical = n.toString();
            return new WorkflowExecutionStore.Definition(TASK_ID, revision, WorkflowEngineMaterial.digest(canonical), canonical,
                    approval, 1, 0, null, null, saved.getStatus(), null);
        }

        @Override public void close() throws Exception { user.close(); }
    }
}
