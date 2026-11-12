package com.aetheris.mcp.client;

import com.aetheris.mcp.security.CurrentUser;
import com.aetheris.mcp.config.McpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.client.RestClient;

/** 本地演示用学习客户端：带显式 dev 头访问 learning-service 的公开 API。 */
public class DevLearningReadClient implements LearningReadClient {

    private final RestClient restClient;
    private final McpProperties properties;

    public DevLearningReadClient(String baseUrl, McpProperties properties) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.properties = properties;
    }

    @Override
    public JsonNode learningPath(CurrentUser user) {
        JsonNode paths = get("/api/learning/paths", user);
        JsonNode progress = get("/api/learning/progress", user);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("paths", paths);
        result.set("progress", progress);
        return result;
    }

    @Override
    public JsonNode lab(CurrentUser user, String labCode) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("lab", get("/api/learning/labs/" + labCode, user));
        return result;
    }

    @Override
    public JsonNode submissions(CurrentUser user, String labCode) {
        return get("/api/learning/labs/" + labCode + "/submissions?limit=10", user);
    }

    @Override
    public JsonNode requestLabPublish(CurrentUser user, String labCode, String reason) {
        throw new McpToolException("SECURITY_NOT_INSTALLED",
                "发布审批需要 v2 安全密钥安装；本地演示模式不开放写操作。");
    }

    @Override
    public JsonNode approval(CurrentUser user, long approvalId) {
        throw new McpToolException("SECURITY_NOT_INSTALLED",
                "审批查询需要 v2 安全密钥安装；本地演示模式不可用。");
    }

    private JsonNode get(String path, CurrentUser user) {
        try {
            return restClient.get().uri(path)
                    .header("X-Aetheris-Dev-User", user == null ? properties.getDevUser() : user.username())
                    .retrieve().body(JsonNode.class);
        } catch (RuntimeException failure) {
            throw McpToolException.unavailable("学习服务不可用：" + failure.getMessage());
        }
    }
}
