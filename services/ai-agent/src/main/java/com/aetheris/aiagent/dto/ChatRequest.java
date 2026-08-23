package com.aetheris.aiagent.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ChatRequest(@NotEmpty List<ChatMessage> messages) {
}
