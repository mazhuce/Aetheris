package com.aetheris.auth.dto;

import com.aetheris.auth.model.DataScope;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 资源授权更新请求：主体与资源类型不可变，只允许调整范围、动作与启停。 */
public record GrantUpdateRequest(
        DataScope dataScope,
        @NotEmpty List<String> actions,
        Boolean enabled) {
}