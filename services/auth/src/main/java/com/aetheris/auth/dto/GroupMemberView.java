package com.aetheris.auth.dto;

/** 用户组成员视图（含用户名与昵称，便于列表展示）。 */
public record GroupMemberView(Long userId, String username, String nickname) {
}