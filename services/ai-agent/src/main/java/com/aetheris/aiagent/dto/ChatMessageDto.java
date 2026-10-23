package com.aetheris.aiagent.dto;

public record ChatMessageDto(@jakarta.validation.constraints.Size(max=16) String role,
        @jakarta.validation.constraints.Size(max=6000) String content) {
}
