package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 列表可见性请求：返回可确定性收敛的根级 id 范围或 OPAQUE。 */
public record VisibilityRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 32) String resourceType,
        @NotBlank @Size(max = 32) String action) {
}