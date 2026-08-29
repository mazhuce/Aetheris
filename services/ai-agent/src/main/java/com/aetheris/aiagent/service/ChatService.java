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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 会话与对话编排。
 *
 * <p>从 controller 抽出来的原因：原实现把 HTTP 客户端调用、限流、参数校验、事务全塞在
 * controller 里，无法单测，也没法在此基础上加工具调用循环。现在 controller 只做协议转换，
 * 编排逻辑在这里，模型调用通过 {@link LlmClient} 端口。
 */
@Service
public class ChatService {

    private static final int MAX_MESSAGES = 50;
    private static final int MAX_TOTAL_CHARS = 20_000;
    private static final String DEFAULT_TITLE = "新对话";
    private static final Set<String> ALLOWED_ROLES = Set.of("user", "assistant");

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

    public List<ChatConversation> listConversations(String username) {
        return conversationService.list(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getOwnerUsername, username)
                        .orderByDesc(ChatConversation::getUpdatedAt));
    }

    public ChatConversation createConversation(String username) {
        ChatConversation conversation = new ChatConversation();
        conversation.setOwnerUsername(username);
        conversation.setTitle(DEFAULT_TITLE);
        conversationService.save(conversation);
        return conversation;
    }

    /** 返回 null 表示会话不存在或不属于该用户，两种情况对外一律按「不存在」处理。 */
    public List<ChatMessage> listMessages(Long conversationId, String username) {
        if (findOwned(conversationId, username) == null) {
            return null;
        }
        return messageService.list(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getId));
    }

    @Transactional
    public boolean deleteConversation(Long conversationId, String username) {
        if (findOwned(conversationId, username) == null) {
            return false;
        }
        if (!conversationService.removeById(conversationId)) {
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
        if (messages.size() > MAX_MESSAGES) {
            return "单次消息条数过多";
        }
        int totalChars = messages.stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        if (totalChars > MAX_TOTAL_CHARS) {
            return "单次内容过长";
        }
        if (messages.stream().anyMatch(message -> !ALLOWED_ROLES.contains(message.role()))) {
            return "消息角色不合法";
        }
        ChatMessageDto last = messages.get(messages.size() - 1);
        if (!"user".equals(last.role()) || last.content() == null || last.content().isBlank()) {
            return "最后一条消息必须是用户提问";
        }
        return null;
    }

    public ChatConversation findOwned(Long conversationId, String username) {
        return conversationService.getOne(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getId, conversationId)
                        .eq(ChatConversation::getOwnerUsername, username));
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
            ChatConversation conversation, List<ChatMessageDto> messages, boolean dataMode) {
        ChatMessageDto lastUser = messages.get(messages.size() - 1);
        saveMessage(conversation.getId(), "user", lastUser.content());
        touchConversation(conversation, lastUser.content());

        List<LlmMessage> history = new ArrayList<>(messages.size());
        for (ChatMessageDto message : messages) {
            history.add(new LlmMessage(message.role(), message.content()));
        }

        LlmReply reply = dataMode
                ? llmClient.chatWithTools(history, AgentPrompts.DATA_ANALYST)
                : llmClient.chat(history);
        saveMessage(conversation.getId(), "assistant", reply.content());
        return reply;
    }

    /** 首轮提问用作会话标题，让侧边栏列表可辨识。 */
    private void touchConversation(ChatConversation conversation, String firstQuestion) {
        if (conversation.getTitle() == null
                || conversation.getTitle().isBlank()
                || DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(truncate(firstQuestion, 30));
        }
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationService.updateById(conversation);
    }

    private void saveMessage(Long conversationId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        messageService.save(message);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return DEFAULT_TITLE;
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) + "…" : cleaned;
    }
}
