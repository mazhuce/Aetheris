package com.aetheris.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAuthUserRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(min = 12, max = 128) String password,
        @Size(max = 64) String nickname,
        @Email @Size(max = 128) String email,
        @Size(max = 32) String phone,
        @NotBlank @Pattern(regexp = "admin|viewer") String role,
        @NotBlank @Pattern(regexp = "active|disabled") String status) {
}
