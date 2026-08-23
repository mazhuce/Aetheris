package com.aetheris.aiagent.service.impl;

import com.aetheris.aiagent.entity.ChatConversation;
import com.aetheris.aiagent.mapper.ChatConversationMapper;
import com.aetheris.aiagent.service.ChatConversationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation>
        implements ChatConversationService {
}
