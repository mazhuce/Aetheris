package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 用户组成员批量添加请求。 */
public record GroupMembersRequest(
        @NotEmpty List<Long> userIds) {
}