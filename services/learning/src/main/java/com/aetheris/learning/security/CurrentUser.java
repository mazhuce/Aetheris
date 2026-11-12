package com.aetheris.learning.security;

/**
 * 学习域当前访问者。只有经过网关 USER 证明校验（或显式开启的本地演示模式）后才会创建。
 */
public record CurrentUser(long uid, String username, String compactProof) {

    public static final String ATTRIBUTE = "aetheris.learning.currentUser";

    public boolean hasProof() {
        return compactProof != null && !compactProof.isBlank();
    }
}
