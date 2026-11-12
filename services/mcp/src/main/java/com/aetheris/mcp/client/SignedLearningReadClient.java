package com.aetheris.mcp.client;

import com.aetheris.mcp.security.CurrentUser;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 通过 learning-service 内部 v2 接口读取当前学员的学习数据。 */
public class SignedLearningReadClient implements LearningReadClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final SignedHttpClient transport;

    public SignedLearningReadClient(SignedHttpClient transport) {
        this.transport = transport;
    }

    @Override
    public JsonNode learningPath(CurrentUser user) {
        return post(user, "learning-path", "learning.mcp.path", Map.of());
    }

    @Override
    public JsonNode lab(CurrentUser user, String labCode) {
        return post(user, "lab", "learning.mcp.lab", Map.of("labCode", labCode));
    }

    @Override
    public JsonNode submissions(CurrentUser user, String labCode) {
        return post(user, "submissions", "learning.mcp.submissions", Map.of("labCode", labCode));
    }

    @Override
    public JsonNode requestLabPublish(CurrentUser user, String labCode, String reason) {
        return post(user, "lab-publish-request", "learning.mcp.publish-request",
                Map.of("labCode", labCode, "reason", reason == null ? "" : reason));
    }

    @Override
    public JsonNode approval(CurrentUser user, long approvalId) {
        if (user == null || !user.hasProof()) throw McpToolException.unauthorized();
        try {
            var pending = transport.sendAsync(new SignedHttpClient.Request("learning", "GET",
                    "/internal/v2/mcp/approvals/" + approvalId, "learning.mcp.approval", new byte[0],
                    user.compactProof()));
            SignedHttpClient.Response response;
            try {
                response = pending.get(10, TimeUnit.SECONDS);
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
            if (response.status() != 200) {
                throw McpToolException.unavailable("学习服务返回状态 " + response.status());
            }
            return JSON.readTree(response.body());
        } catch (McpToolException expected) {
            throw expected;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw McpToolException.unavailable("审批查询被中断");
        } catch (Exception failure) {
            throw McpToolException.unavailable("审批查询失败");
        }
    }

    private JsonNode post(CurrentUser user, String path, String operation, Object body) {
        if (user == null || !user.hasProof()) throw McpToolException.unauthorized();
        try {
            byte[] payload = JSON.writeValueAsBytes(body);
            var pending = transport.sendAsync(new SignedHttpClient.Request("learning", "POST",
                    "/internal/v2/mcp/" + path, operation, payload, user.compactProof()));
            SignedHttpClient.Response response;
            try {
                response = pending.get(10, TimeUnit.SECONDS);
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
            if (response.status() != 200) {
                throw McpToolException.unavailable("学习服务返回状态 " + response.status());
            }
            return JSON.readTree(response.body());
        } catch (McpToolException expected) {
            throw expected;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw McpToolException.unavailable("学习服务调用被中断");
        } catch (Exception failure) {
            throw McpToolException.unavailable("学习服务调用失败");
        }
    }
}
