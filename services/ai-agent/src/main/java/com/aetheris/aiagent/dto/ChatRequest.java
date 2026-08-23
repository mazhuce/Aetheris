package com.aetheris.aiagent.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ChatRequest(@NotNull Long conversationId, @NotEmpty List<ChatMessageDto> messages) {
}
