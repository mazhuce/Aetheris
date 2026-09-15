package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchPermissionCheckRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 32) String resourceType,
        @NotBlank @Size(max = 32) String action,
        @NotEmpty @Size(max = 1000) List<BatchPermissionCheckItem> items) { }
