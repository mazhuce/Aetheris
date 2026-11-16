package com.aetheris.aiagent.service;

import com.aetheris.aiagent.agent.AgentPrompts;
import com.aetheris.aiagent.dto.ChatMessageDto;
import com.aetheris.aiagent.entity.ChatConversation;
import com.aetheris.aiagent.entity.ChatMessage;
import com.aetheris.aiagent.llm.LlmClient;
import com.aetheris.aiagent.llm.LlmMessage;
import com.aetheris.aiagent.llm.LlmReply;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话与对话编排。
 *
 * <p>从 controller 抽出来的原因：原实现把 HTTP 客户端调用、限流、参数校验、事务全塞在
 * controller 里，无法单测，也没法在此基础上加工具调用循环。现在 controller 只做协议转换，
 * 编排逻辑在这里，模型调用通过 {@link LlmClient} 端口。
 */
@Service
public class ChatService {
    private ChatRunStore runStore;
    private com.aetheris.aiagent.llm.AiBudgetLimits limits=com.aetheris.aiagent.llm.AiBudgetLimits.defaults();
    @org.springframework.beans.factory.annotation.Autowired
    public void setRunStore(ChatRunStore store){this.runStore=store;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setBudgetLimits(com.aetheris.aiagent.llm.AiBudgetLimits limits){this.limits=limits;}

    private static final int MAX_MESSAGES = 50;
    private static final int MAX_TOTAL_CHARS = 20_000;
    private static final String DEFAULT_TITLE = "新对话";

    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;
    private final LlmClient llmClient;

    public ChatService(
            ChatConversationService conversationService,
            ChatMessageService messageService,
            LlmClient llmClient) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.llmClient = llmClient;
    }

    public List<ChatConversation> listConversations(ChatOwner owner) {
        return conversationService.list(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getOwnerUid, owner.uid())
                        .orderByDesc(ChatConversation::getUpdatedAt));
    }

    public ChatConversation createConversation(ChatOwner owner) {
        ChatConversation conversation = new ChatConversation();
        conversation.setOwnerUid(owner.uid());
        conversation.setOwnerUsername(owner.username());
        conversation.setTitle(DEFAULT_TITLE);
        conversationService.save(conversation);
        return conversation;
    }

    /** 返回 null 表示会话不存在或不属于该用户，两种情况对外一律按「不存在」处理。 */
    public List<ChatMessage> listMessages(Long conversationId, ChatOwner owner) {
        if (findOwned(conversationId, owner) == null) {
            return null;
        }
        return messageService.list(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getId));
    }

    @Transactional
    public boolean deleteConversation(Long conversationId, ChatOwner owner) {
        if (!conversationService.remove(new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getId, conversationId).eq(ChatConversation::getOwnerUid, owner.uid()))) {
            return false;
        }
        messageService.remove(
                new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, conversationId));
        return true;
    }

    /**
     * 校验请求内容。
     *
     * @return 不合法时返回原因，合法时返回 null
     */
    public String validate(List<ChatMessageDto> messages) {
        if(messages==null || messages.isEmpty() || messages.stream().anyMatch(java.util.Objects::isNull))return "消息不能为空";
        if (messages.size() > MAX_MESSAGES) {
            return "单次消息条数过多";
        }
        long totalChars = messages.stream()
                .mapToLong(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        if (totalChars > MAX_TOTAL_CHARS) {
            return "单次内容过长";
        }
        ChatMessageDto last = messages.get(messages.size() - 1);
        if (!"user".equals(last.role()) || last.content() == null || last.content().isBlank()) {
            return "最后一条消息必须是用户提问";
        }
        if(last.content().length()>6000)return "用户提问过长";
        return null;
    }

    public ChatConversation findOwned(Long conversationId, ChatOwner owner) {
        return conversationService.getOne(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getId, conversationId)
                        .eq(ChatConversation::getOwnerUid, owner.uid()));
    }

    /**
     * 执行一轮对话：落库用户提问 → 调用模型 → 落库回复。
     *
     * <p>用户消息先落库再调模型，因此模型调用失败时提问仍然保留 —— 用户刷新后能看到自己问过
     * 什么，而不是消息凭空消失。模型失败由调用方捕获 {@code LlmException} 处理。
     *
     * @param dataMode 启用后模型可调用工具检索元数据、执行只读查询；关闭则为普通对话
     */
    public LlmReply chat(
            ChatOwner owner, ChatConversation conversation, List<ChatMessageDto> messages, boolean dataMode) {
        return chat(owner, conversation, messages, dataMode, false, null);
    }

    public LlmReply chat(
            ChatOwner owner, ChatConversation conversation, List<ChatMessageDto> messages, boolean dataMode,
            boolean expertMode, String context) {
        String invalid=validate(messages);
        if(invalid!=null)throw new IllegalArgumentException(invalid);
        if(runStore==null)throw new IllegalStateException("会话执行存储未配置");
        ChatMessageDto lastUser = messages.get(messages.size() - 1);
        var current=com.aetheris.aiagent.llm.AiRunContext.current();
        var run=current==null?new com.aetheris.aiagent.llm.AiRunContext(limits):current;
        boolean local=current==null;
        if(local)com.aetheris.aiagent.llm.AiRunContext.attach(run);
        ChatRunStore.Claim claim;
        try {
            run.check();
            claim=runStore.claim(conversation.getId(),owner,
                    lastUser.content(),run.remainingMillis());
        } catch(RuntimeException failure) {
            if(local)com.aetheris.aiagent.llm.AiRunContext.clear();
            throw failure;
        }
        try {
            List<LlmMessage> history=new ArrayList<>(claim.history());
            history.add(new LlmMessage("user",lastUser.content()));
            LlmReply reply;
            if (expertMode) {
                String prompt = AgentPrompts.DATA_EXPERT + (context == null || context.isBlank() ? ""
                        : "\n\n## 当前学习上下文\n" + context.substring(0, Math.min(context.length(), 600)));
                reply = dataMode ? llmClient.chatWithTools(history, prompt) : llmClient.chat(history, prompt);
            } else {
                reply = dataMode ? llmClient.chatWithTools(history, AgentPrompts.DATA_ANALYST) : llmClient.chat(history);
            }
            return run.commit(()->{
                if(!runStore.complete(claim,reply.content()))
                    throw new com.aetheris.aiagent.llm.LlmException(com.aetheris.aiagent.llm.LlmException.Kind.UNAVAILABLE,
                            "会话执行已过期，回复未写入",null);
                return reply;
            });
        } finally {
            try { runStore.cancel(claim); }
            catch(RuntimeException cleanup) { org.slf4j.LoggerFactory.getLogger(ChatService.class).warn("AI run claim cleanup failed; database deadline remains the fence"); }
            finally { if(local)com.aetheris.aiagent.llm.AiRunContext.clear(); }
        }
    }

    /**
     * ChatBI 智能问数：单问句、无会话状态的一次性问答。
     *
     * <p>与 {@link #chat} 的区别：不落库、无多轮上下文，返回内容按 CHAT_BI
     * 提示包含结论、口径与 SQL。适合「问一句话拿一个数」的场景；
     * 需要追问与上下文请用 AI 助手的 dataMode 对话。
     */
    public LlmReply chatBi(String question) {
        return llmClient.chatWithTools(
                List.of(new LlmMessage("user", question)),
                AgentPrompts.CHAT_BI);
    }

}
