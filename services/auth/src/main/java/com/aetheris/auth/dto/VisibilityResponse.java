package com.aetheris.auth.dto;

import java.util.List;

/**
 * 可见性响应：mode 为 ALL（全量可见）/ IDS（仅 ids）/ OPAQUE（需逐条调用
 * /internal/permissions/check 判定）。
 */
public record VisibilityResponse(String mode, List<Long> ids) {
}