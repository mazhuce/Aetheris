package com.aetheris.aiagent.service.impl;

import com.aetheris.aiagent.entity.ChatMessage;
import com.aetheris.aiagent.mapper.ChatMessageMapper;
import com.aetheris.aiagent.service.ChatMessageService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage>
        implements ChatMessageService {
}
