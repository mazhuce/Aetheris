package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import com.aetheris.auth.validation.ValidUsername;

public record LoginRequest(
        @ValidUsername String username,
        @NotBlank String password) {
}
