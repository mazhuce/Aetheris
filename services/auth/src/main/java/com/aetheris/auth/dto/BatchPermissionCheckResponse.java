package com.aetheris.auth.dto;

import java.util.List;

public record BatchPermissionCheckResponse(List<Long> allowedIds) { }
