package com.aetheris.auth.dto;

import com.aetheris.auth.model.DataScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 资源授权新增请求：授给用户/用户组/角色，可携带数据范围。 */
public record GrantRequest(
        @NotBlank @Pattern(regexp = "user|group|role") String granteeType,
        @NotNull Long granteeId,
        @NotBlank @Size(max = 32) String resourceType,
        Long resourceId,
        DataScope dataScope,
        @NotEmpty List<String> actions,
        Boolean enabled) {
}