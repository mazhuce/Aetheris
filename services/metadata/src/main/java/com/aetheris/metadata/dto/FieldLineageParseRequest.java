package com.aetheris.metadata.dto;
import jakarta.validation.constraints.NotBlank;
public record FieldLineageParseRequest(@NotBlank String sql, String relationType) {}
