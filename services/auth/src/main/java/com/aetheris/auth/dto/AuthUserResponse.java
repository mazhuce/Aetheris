package com.aetheris.auth.dto;

import com.aetheris.auth.entity.AuthUser;

import java.time.OffsetDateTime;

public record AuthUserResponse(
        Long id,
        String username,
        String nickname,
        String email,
        String phone,
        String role,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static AuthUserResponse from(AuthUser user) {
        return new AuthUserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
