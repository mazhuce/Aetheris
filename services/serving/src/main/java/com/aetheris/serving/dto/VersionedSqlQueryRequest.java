package com.aetheris.serving.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
public record VersionedSqlQueryRequest(@NotNull Long datasourceId, @NotBlank @Size(max=20000) String sql) {}
