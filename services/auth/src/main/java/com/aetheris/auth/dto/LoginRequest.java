package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
        @NotBlank @Pattern(regexp = "[^|\\p{Cntrl}]{1,64}") String username,
        @NotBlank String password) {
}
