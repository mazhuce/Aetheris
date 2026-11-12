package com.aetheris.mcp.web;

import com.aetheris.mcp.audit.McpAuditRepository;
import com.aetheris.mcp.client.LearningReadClient;
import com.aetheris.mcp.client.McpToolException;
import com.aetheris.mcp.config.McpProperties;
import com.aetheris.mcp.prompts.PromptRegistry;
import com.aetheris.mcp.protocol.McpProtocol;
import com.aetheris.mcp.resources.ResourceService;
import com.aetheris.mcp.security.CurrentUser;
import com.aetheris.mcp.security.ToolRateLimiter;
import com.aetheris.mcp.tasks.McpTask;
import com.aetheris.mcp.tasks.McpTaskRepository;
import com.aetheris.mcp.tools.ToolDefinition;
import com.aetheris.mcp.tools.ToolRegistry;
import com.aetheris.mcp.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * MCP Streamable HTTP 入口。无状态、请求自包含；只读工具直接返回，
 * 高风险能力在 M2 不开放，因此不存在“先执行再审批”的中间态。
 */
@RestController
public class McpController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode EMPTY = JsonNodeFactory.instance.objectNode();

    private final ToolRegistry tools;
    private final ResourceService resources;
    private final PromptRegistry prompts;
    private final ToolRateLimiter rateLimiter;
    private final McpAuditRepository audit;
    private final McpProperties properties;
    private final McpTaskRepository tasks;
    private final LearningReadClient learning;

    public McpController(ToolRegistry tools, ResourceService resources, PromptRegistry prompts,
                         ToolRateLimiter rateLimiter, McpAuditRepository audit, McpProperties properties,
                         McpTaskRepository tasks, LearningReadClient learning) {
        this.tools = tools;
        this.resources = resources;
        this.prompts = prompts;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.properties = properties;
        this.tasks = tasks;
        this.learning = learning;
    }

    @PostMapping("/api/mcp")
    public ResponseEntity<JsonNode> rpc(@RequestBody(required = false) JsonNode body,
                                        @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocol,
                                        @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        if (body == null || !body.isObject()) {
            return ResponseEntity.ok(error(null, McpProtocol.INVALID_REQUEST, "请求必须是 JSON-RPC 对象"));
        }
        JsonNode id = body.get("id");
        String method = body.path("method").asText("");
        JsonNode params = body.has("params") ? body.get("params") : EMPTY;
        if (!McpProtocol.JSON_RPC.equals(body.path("jsonrpc").asText())) {
            return ResponseEntity.ok(error(id, McpProtocol.INVALID_REQUEST, "jsonrpc 必须为 2.0"));
        }
        if (method.startsWith("notifications/")) {
            return ResponseEntity.accepted().build();
        }
        try {
            JsonNode result = switch (method) {
                case "initialize" -> initialize();
                case "ping" -> JSON.createObjectNode();
                case "tools/list" -> listTools();
                case "tools/call" -> callTool(params, user);
                case "resources/list" -> listResources();
                case "resources/templates/list" -> listResourceTemplates();
                case "resources/read" -> readResource(params, user);
                case "prompts/list" -> listPrompts();
                case "prompts/get" -> getPrompt(params);
                case "tasks/get" -> getTask(params, user);
                case "tasks/list" -> listTasks(params, user);
                default -> null;
            };
            if (result == null) {
                return ResponseEntity.ok(error(id, McpProtocol.METHOD_NOT_FOUND, "不支持的方法：" + method));
            }
            return ResponseEntity.ok(success(id, result));
        } catch (McpToolException failure) {
            return ResponseEntity.ok(error(id, McpProtocol.TOOL_FAILED, failure.getMessage()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.ok(error(id, McpProtocol.INVALID_PARAMS, invalid.getMessage()));
        } catch (RuntimeException unexpected) {
            return ResponseEntity.ok(error(id, McpProtocol.INTERNAL_ERROR, "服务处理失败"));
        }
    }

    /** 供 Web 调试台读取工具目录；不是 MCP 协议的一部分。 */
    @GetMapping("/api/mcp/info")
    public Map<String, Object> info(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser user) {
        ArrayNode toolNodes = JSON.createArrayNode();
        for (ToolDefinition definition : tools.list()) {
            ObjectNode node = toolNodes.addObject();
            node.put("name", definition.name());
            node.put("title", definition.title());
            node.put("description", definition.description());
            node.set("inputSchema", JSON.valueToTree(definition.inputSchema()));
            node.put("riskLevel", definition.riskLevel());
            node.put("requiresApproval", definition.riskLevel() > 0);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("protocolVersion", properties.getProtocolVersion());
        result.put("learner", user.username());
        result.put("tools", toolNodes);
        result.put("resources", resources.list());
        result.put("resourceTemplates", resources.templates());
        result.put("prompts", prompts.list());
        result.put("rateLimitPerMinute", properties.getRateLimitCapacity());
        return result;
    }

    private JsonNode initialize() {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", properties.getProtocolVersion());
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode resourcesCapability = capabilities.putObject("resources");
        resourcesCapability.put("listChanged", false);
        resourcesCapability.put("subscribe", false);
        capabilities.putObject("prompts").put("listChanged", false);
        capabilities.putObject("tasks").put("listChanged", false);
        ObjectNode server = result.putObject("serverInfo");
        server.put("name", "aetheris-mcp");
        server.put("title", "Aetheris 数据平台 MCP");
        server.put("version", "0.2.0");
        result.put("instructions", "Aetheris 只读 MCP 服务。工具调用继承当前用户权限，"
                + "SQL 只允许单条只读查询，所有调用都会进入审计。");
        return result;
    }

    private JsonNode listTools() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode array = result.putArray("tools");
        for (ToolDefinition definition : tools.list()) {
            ObjectNode node = array.addObject();
            node.put("name", definition.name());
            node.put("title", definition.title());
            node.put("description", definition.description());
            node.set("inputSchema", JSON.valueToTree(definition.inputSchema()));
            ObjectNode annotations = node.putObject("annotations");
            annotations.put("readOnlyHint", definition.riskLevel() == 0);
            annotations.put("destructiveHint", false);
            annotations.put("requiresApproval", definition.riskLevel() > 0);
        }
        return result;
    }

    private JsonNode callTool(JsonNode params, CurrentUser user) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : EMPTY;
        Optional<ToolDefinition> definition = tools.find(name);
        if (definition.isEmpty()) {
            throw new IllegalArgumentException("工具不存在：" + name);
        }
        long started = System.nanoTime();
        ToolResult toolResult;
        if (!rateLimiter.tryAcquire(user.uid())) {
            toolResult = ToolResult.error("RATE_LIMITED", "调用频率超出限制，请稍后再试。");
        } else {
            try {
                toolResult = definition.get().call(user, arguments);
            } catch (McpToolException failure) {
                toolResult = ToolResult.error(failure.code(), failure.getMessage());
            } catch (RuntimeException unexpected) {
                toolResult = ToolResult.error("TOOL_FAILED", "工具执行失败");
            }
        }
        int durationMs = (int) ((System.nanoTime() - started) / 1_000_000);
        audit.record(user.uid(), user.username(), "tools/call", name, digest(arguments.toString()),
                toolResult.error() ? "failed" : "succeeded",
                toolResult.error() ? McpProtocol.TOOL_FAILED : null, durationMs);

        ObjectNode result = JSON.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", toolResult.text());
        result.put("isError", toolResult.error());
        if (!toolResult.error() && toolResult.structured() != null) {
            result.set("structuredContent", toolResult.structured());
        }
        if (toolResult.error() && toolResult.errorCode() != null) {
            result.putObject("_meta").put("errorCode", toolResult.errorCode());
        }
        return result;
    }

    private JsonNode listResources() {
        ObjectNode result = JSON.createObjectNode();
        result.set("resources", resources.list());
        return result;
    }

    private JsonNode listResourceTemplates() {
        ObjectNode result = JSON.createObjectNode();
        result.set("resourceTemplates", resources.templates());
        return result;
    }

    private JsonNode readResource(JsonNode params, CurrentUser user) {
        String uri = params.path("uri").asText("");
        long started = System.nanoTime();
        ObjectNode result = JSON.createObjectNode();
        ArrayNode contents = result.putArray("contents");
        resources.read(uri, user).forEach(item -> {
            ObjectNode node = contents.addObject();
            node.put("uri", String.valueOf(item.get("uri")));
            node.put("mimeType", String.valueOf(item.get("mimeType")));
            node.put("text", String.valueOf(item.get("text")));
        });
        audit.record(user.uid(), user.username(), "resources/read", null, digest(uri), "succeeded", null,
                (int) ((System.nanoTime() - started) / 1_000_000));
        return result;
    }

    private JsonNode listPrompts() {
        ObjectNode result = JSON.createObjectNode();
        result.set("prompts", prompts.list());
        return result;
    }

    private JsonNode getPrompt(JsonNode params) {
        return prompts.get(params.path("name").asText(""), params.get("arguments"));
    }

    private JsonNode getTask(JsonNode params, CurrentUser user) {
        long taskId = params.path("taskId").asLong(0);
        if (taskId <= 0) throw new IllegalArgumentException("缺少 taskId");
        McpTask task = tasks.find(taskId, user.uid())
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
        return taskJson(refresh(task, user));
    }

    private JsonNode listTasks(JsonNode params, CurrentUser user) {
        int limit = Math.clamp(params.path("limit").asInt(20), 1, 50);
        ObjectNode result = JSON.createObjectNode();
        ArrayNode array = result.putArray("tasks");
        for (McpTask task : tasks.list(user.uid(), limit)) {
            array.add(taskJson(refresh(task, user)));
        }
        return result;
    }

    /** 任务状态以 learning 审批单为准，读取时同步一次，避免两边状态漂移。 */
    private McpTask refresh(McpTask task, CurrentUser user) {
        if (task.terminal() || task.approvalId() == null) return task;
        try {
            JsonNode approval = learning.approval(user, task.approvalId());
            String mapped = switch (approval.path("status").asText("")) {
                case "approved" -> "completed";
                case "rejected" -> "rejected";
                default -> null;
            };
            if (mapped != null) {
                tasks.update(task.id(), mapped, approval.toString(), null);
                return tasks.find(task.id(), user.uid()).orElse(task);
            }
        } catch (RuntimeException ignored) {
            // 审批服务不可用时保留本地状态，由下次查询重试。
        }
        return task;
    }

    private static ObjectNode taskJson(McpTask task) {
        ObjectNode node = JSON.createObjectNode();
        node.put("taskId", task.id());
        node.put("type", task.type());
        node.put("status", task.status());
        if (task.approvalId() != null) node.put("approvalId", task.approvalId());
        if (task.labCode() != null) node.put("labCode", task.labCode());
        int progress = switch (task.status()) {
            case "pending_approval" -> 50;
            case "completed", "rejected", "failed" -> 100;
            default -> 10;
        };
        node.put("progress", progress);
        node.set("payload", task.payload());
        node.set("result", task.result());
        if (task.error() != null) node.put("error", task.error());
        node.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
        node.put("updatedAt", task.updatedAt() == null ? null : task.updatedAt().toString());
        return node;
    }

    private static ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", McpProtocol.JSON_RPC);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        response.set("result", result);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", McpProtocol.JSON_RPC);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception missing) {
            return "unavailable";
        }
    }
}
