package com.aetheris.mcp.security;

/** 已认证的 MCP 调用者。compactProof 仅在网关路径下存在，用于向下游转发同一用户身份。 */
public record CurrentUser(long uid, String username, String compactProof) {

    public static final String ATTRIBUTE = "aetheris.mcp.currentUser";

    public boolean hasProof() {
        return compactProof != null && !compactProof.isBlank();
    }
}
