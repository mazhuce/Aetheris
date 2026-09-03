package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 网关粗粒度写入口判定请求。service 为 metadata/serving/workflow/quality/ingestion/governance/system。 */
public record MutateCheckRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 32) String service) {
}