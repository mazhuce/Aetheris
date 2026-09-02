package com.aetheris.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** ChatBI 智能问数请求：单个自然语言问题。 */
public record ChatBiRequest(
        @NotBlank @Size(max = 500) String question) {
}
