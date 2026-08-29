package com.aetheris.aiagent.llm;

import java.util.List;

/**
 * 大模型对话的端口抽象。
 *
 * <p>存在这一层的原因：业务逻辑（会话归属、限流、持久化、后续的工具调用循环）不应绑定到
 * 具体模型厂商。当前实现走 Spring AI 的 DeepSeek starter，换成其他 provider 时只替换
 * 适配器，上层不动。
 */
public interface LlmClient {

    /**
     * 发起一轮对话。
     *
     * @param messages 完整对话历史，末条必须是用户消息
     * @return 模型回复
     * @throws LlmException 调用失败（网络、鉴权、限流、内容策略等）
     */
    LlmReply chat(List<LlmMessage> messages);

    /**
     * 发起一轮带工具调用的对话。
     *
     * <p>模型可在回答前多次调用工具（检索表、看结构、执行查询），由框架驱动循环直到产出最终答复。
     * 与 {@link #chat} 分开而不是加参数，是为了让「纯闲聊」与「数据问答」两条路径的
     * 系统提示与超时策略能各自演进。
     *
     * @param messages     完整对话历史，末条必须是用户消息
     * @param systemPrompt 系统提示，说明 agent 的职责与工具使用纪律
     * @return 模型回复，含实际使用的模型标识与 token 用量
     * @throws LlmException 调用失败
     */
    LlmReply chatWithTools(List<LlmMessage> messages, String systemPrompt);

    /** 当前使用的模型标识，用于回显与审计。 */
    String model();
}
