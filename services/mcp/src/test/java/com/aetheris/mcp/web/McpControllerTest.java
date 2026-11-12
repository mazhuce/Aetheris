package com.aetheris.mcp.web;

import com.aetheris.mcp.audit.McpAuditRepository;
import com.aetheris.mcp.client.LearningReadClient;
import com.aetheris.mcp.client.PlatformReadClient;
import com.aetheris.mcp.config.McpProperties;
import com.aetheris.mcp.prompts.PromptRegistry;
import com.aetheris.mcp.resources.ResourceService;
import com.aetheris.mcp.security.CurrentUser;
import com.aetheris.mcp.security.ToolRateLimiter;
import com.aetheris.mcp.tasks.McpTask;
import com.aetheris.mcp.tasks.McpTaskRepository;
import com.aetheris.mcp.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CurrentUser USER = new CurrentUser(9001L, "dev-learner", "proof");

    private McpController controller;
    private McpProperties properties;
    private StubLearningClient learning;
    private McpTaskRepository tasks;

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        properties.setRateLimitCapacity(2);
        var platform = new StubPlatformClient();
        learning = new StubLearningClient();
        tasks = mock(McpTaskRepository.class);
        when(tasks.insert(anyLong(), anyString(), anyString(), anyString(), any(), any(), anyString())).thenReturn(7L);
        controller = new McpController(new ToolRegistry(platform, learning, tasks, properties),
                new ResourceService(platform, learning), new PromptRegistry(),
                new ToolRateLimiter(properties), mock(McpAuditRepository.class), properties, tasks, learning);
    }

    @Test
    void initializeReportsProtocolAndCapabilities() {
        JsonNode response = controller.rpc(request("initialize", null), "2026-07-28", USER).getBody();

        assertThat(response.path("result").path("protocolVersion").asText()).isEqualTo("2026-07-28");
        assertThat(response.path("result").path("capabilities").has("tools")).isTrue();
        assertThat(response.path("result").path("serverInfo").path("name").asText()).isEqualTo("aetheris-mcp");
    }

    @Test
    void toolsListExposesOnlyReadOnlyTools() {
        JsonNode response = controller.rpc(request("tools/list", null), null, USER).getBody();
        JsonNode tools = response.path("result").path("tools");

        assertThat(tools).hasSize(11);
        assertThat(tools).allSatisfy(tool ->
                assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isFalse());
        assertThat(tools.toString()).contains("query_readonly_sql").contains("get_learning_path")
                .contains("draft_lab_publish").contains("draft_quality_rule");
    }

    @Test
    void toolsCallReturnsStructuredContent() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", "get_learning_path");
        params.set("arguments", JsonNodeFactory.instance.objectNode());

        JsonNode response = controller.rpc(request("tools/call", params), null, USER).getBody();
        JsonNode result = response.path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText()).contains("de-foundation");
        assertThat(result.path("structuredContent").path("paths")).isNotEmpty();
    }

    @Test
    void unknownToolReturnsInvalidParams() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", "no_such_tool");

        JsonNode response = controller.rpc(request("tools/call", params), null, USER).getBody();

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void rateLimitIsEnforcedPerUser() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", "get_learning_path");
        params.set("arguments", JsonNodeFactory.instance.objectNode());

        assertThat(controller.rpc(request("tools/call", params), null, USER).getBody()
                .path("result").path("isError").asBoolean()).isFalse();
        assertThat(controller.rpc(request("tools/call", params), null, USER).getBody()
                .path("result").path("isError").asBoolean()).isFalse();
        JsonNode limited = controller.rpc(request("tools/call", params), null, USER).getBody()
                .path("result");

        assertThat(limited.path("isError").asBoolean()).isTrue();
        assertThat(limited.path("_meta").path("errorCode").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void resourcesReadUsesForwardedIdentity() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", "aetheris://learning/path");

        JsonNode response = controller.rpc(request("resources/read", params), null, USER).getBody();

        assertThat(response.path("result").path("contents").get(0).path("text").asText())
                .contains("de-foundation");
    }

    @Test
    void promptsGetRequiresArguments() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", "sql_review");
        params.set("arguments", JsonNodeFactory.instance.objectNode().put("sql", "SELECT 1"));

        JsonNode response = controller.rpc(request("prompts/get", params), null, USER).getBody();

        assertThat(response.path("result").path("messages").get(0).path("content").path("text").asText())
                .contains("SELECT 1");
    }

    @Test
    void notificationsAreAcceptedWithoutBody() {
        ObjectNode notification = JsonNodeFactory.instance.objectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");

        var response = controller.rpc(notification, null, USER);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void draftPublishCreatesApprovalAndTaskWithoutExecuting() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", "draft_lab_publish");
        params.set("arguments", JsonNodeFactory.instance.objectNode()
                .put("labCode", "l1-valid-orders").put("reason", "内容已审核"));

        JsonNode result = controller.rpc(request("tools/call", params), null, USER).getBody().path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("taskId").asLong()).isEqualTo(7L);
        assertThat(result.path("structuredContent").path("approvalId").asLong()).isEqualTo(42L);
        assertThat(result.path("structuredContent").path("status").asText()).isEqualTo("pending_approval");
        assertThat(learning.publishRequests).isEqualTo(1);
    }

    @Test
    void taskStatusFollowsApprovalDecision() {
        McpTask pending = new McpTask(7L, 9001L, "dev-learner", "LAB_PUBLISH_APPROVAL", "pending_approval",
                42L, "l1-valid-orders", JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                null, Instant.now(), Instant.now());
        McpTask completed = new McpTask(7L, 9001L, "dev-learner", "LAB_PUBLISH_APPROVAL", "completed",
                42L, "l1-valid-orders", pending.payload(), JsonNodeFactory.instance.objectNode().put("ok", true),
                null, Instant.now(), Instant.now());
        when(tasks.find(7L, 9001L)).thenReturn(Optional.of(pending)).thenReturn(Optional.of(completed));
        learning.approvalStatus = "approved";
        ObjectNode params = JsonNodeFactory.instance.objectNode().put("taskId", 7L);

        JsonNode result = controller.rpc(request("tasks/get", params), null, USER).getBody().path("result");

        assertThat(result.path("status").asText()).isEqualTo("completed");
        assertThat(result.path("progress").asInt()).isEqualTo(100);
    }

    private static JsonNode request(String method, JsonNode params) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        if (params != null) request.set("params", params);
        return request;
    }

    private static final class StubPlatformClient implements PlatformReadClient {
        @Override
        public JsonNode searchTables(CurrentUser user, String keyword, String layer) {
            return JsonNodeFactory.instance.arrayNode();
        }

        @Override
        public JsonNode describeTable(CurrentUser user, long tableId) {
            return JsonNodeFactory.instance.objectNode().put("tableId", tableId);
        }

        @Override
        public JsonNode query(CurrentUser user, long datasourceId, String sql) {
            return JsonNodeFactory.instance.objectNode().put("rows", 0);
        }

        @Override
        public JsonNode searchMetrics(CurrentUser user, String keyword) {
            return JsonNodeFactory.instance.arrayNode();
        }

        @Override
        public JsonNode queryMetric(CurrentUser user, String metricCode, List<String> dimensions, String grain,
                                    String startDate, String endDate) {
            return JsonNodeFactory.instance.objectNode().put("metric", metricCode);
        }
    }

    private static final class StubLearningClient implements LearningReadClient {
        private int publishRequests;
        private String approvalStatus = "pending";

        @Override
        public JsonNode learningPath(CurrentUser user) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            result.putArray("paths").addObject().put("code", "de-foundation").put("title", "数据开发基础路径");
            result.putObject("progress").put("percent", 0);
            return result;
        }

        @Override
        public JsonNode lab(CurrentUser user, String labCode) {
            return JsonNodeFactory.instance.objectNode().put("code", labCode);
        }

        @Override
        public JsonNode submissions(CurrentUser user, String labCode) {
            return JsonNodeFactory.instance.arrayNode();
        }

        @Override
        public JsonNode requestLabPublish(CurrentUser user, String labCode, String reason) {
            publishRequests++;
            return JsonNodeFactory.instance.objectNode()
                    .put("approvalId", 42L).put("status", "pending").put("labCode", labCode);
        }

        @Override
        public JsonNode approval(CurrentUser user, long approvalId) {
            return JsonNodeFactory.instance.objectNode()
                    .put("approvalId", approvalId).put("status", approvalStatus);
        }
    }
}
