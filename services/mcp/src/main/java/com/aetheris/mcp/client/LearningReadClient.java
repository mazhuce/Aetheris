package com.aetheris.mcp.client;

import com.aetheris.mcp.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;

/** 学习域只读能力端口，由 learning-service 的内部 v2 接口提供。 */
public interface LearningReadClient {

    JsonNode learningPath(CurrentUser user);

    JsonNode lab(CurrentUser user, String labCode);

    JsonNode submissions(CurrentUser user, String labCode);

    /** 高风险动作：只创建审批单，不执行发布。 */
    JsonNode requestLabPublish(CurrentUser user, String labCode, String reason);

    JsonNode approval(CurrentUser user, long approvalId);
}
