package com.aetheris.serving.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;

public record GrantInput(
        @NotBlank String apiPath,
        @NotBlank String grantee,
        @Pattern(regexp = "user|role") String grantType,
        @Pattern(regexp = "read|write") String scope,
        OffsetDateTime expiresAt,
        @Pattern(regexp = "active|revoked") String status) {
}
