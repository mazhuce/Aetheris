package com.aetheris.mcp.resources;

import com.aetheris.mcp.client.LearningReadClient;
import com.aetheris.mcp.client.McpToolException;
import com.aetheris.mcp.client.PlatformReadClient;
import com.aetheris.mcp.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** MCP Resources：把平台对象映射为可读 URI，读取时仍走同一套权限与审计。 */
@Component
public class ResourceService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PlatformReadClient platform;
    private final LearningReadClient learning;

    public ResourceService(PlatformReadClient platform, LearningReadClient learning) {
        this.platform = platform;
        this.learning = learning;
    }

    public ArrayNode list() {
        ArrayNode resources = JSON.createArrayNode();
        resources.add(resource("aetheris://learning/path", "我的学习路径", "当前学员的路径、进度与技能状态",
                "application/json"));
        return resources;
    }

    public ArrayNode templates() {
        ArrayNode templates = JSON.createArrayNode();
        templates.add(template("aetheris://catalog/tables/{tableId}", "数据表结构", "按表 ID 读取字段与注释", "application/json"));
        templates.add(template("aetheris://learning/lab/{labCode}", "实验任务卡", "按任务编码读取任务定义与最近一次评分",
                "application/json"));
        return templates;
    }

    public List<Map<String, Object>> read(String uri, CurrentUser user) {
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("缺少 resource uri");
        JsonNode content;
        if (uri.equals("aetheris://learning/path")) {
            content = learning.learningPath(user);
        } else if (uri.startsWith("aetheris://learning/lab/")) {
            content = learning.lab(user, tail(uri));
        } else if (uri.startsWith("aetheris://catalog/tables/")) {
            long tableId = parseId(tail(uri));
            content = platform.describeTable(user, tableId);
        } else {
            throw new McpToolException("RESOURCE_NOT_FOUND", "资源不存在：" + uri);
        }
        return List.of(Map.of("uri", uri, "mimeType", "application/json", "text", content.toString()));
    }

    private static ObjectNode resource(String uri, String name, String description, String mimeType) {
        ObjectNode node = JSON.createObjectNode();
        node.put("uri", uri);
        node.put("name", name);
        node.put("description", description);
        node.put("mimeType", mimeType);
        return node;
    }

    private static ObjectNode template(String uriTemplate, String name, String description, String mimeType) {
        ObjectNode node = JSON.createObjectNode();
        node.put("uriTemplate", uriTemplate);
        node.put("name", name);
        node.put("description", description);
        node.put("mimeType", mimeType);
        return node;
    }

    private static String tail(String uri) {
        int slash = uri.lastIndexOf('/');
        return slash < 0 ? "" : uri.substring(slash + 1);
    }

    private static long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new McpToolException("INVALID_ARGUMENT", "资源 ID 必须是整数：" + value);
        }
    }
}
