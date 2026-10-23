package com.aetheris.system.dto;

import com.aetheris.system.entity.SysUser;
import java.time.OffsetDateTime;

/** Contact information is only returned behind the management permission gate. */
public record SysUserResponse(Long id, String username, String nickname, String email, String phone,
                              String status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public static SysUserResponse from(SysUser user) {
        return new SysUserResponse(user.getId(), user.getUsername(), user.getNickname(), user.getEmail(),
                user.getPhone(), user.getStatus(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
