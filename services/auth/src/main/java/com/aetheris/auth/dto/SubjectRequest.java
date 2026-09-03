package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 仅携带主体身份的请求（如系统级管理入口判定）。 */
public record SubjectRequest(
        @NotBlank @Size(max = 64) String username) {
}