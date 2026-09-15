package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record BatchPermissionCheckItem(@NotNull Long resourceId, Map<String, String> ctx) { }
