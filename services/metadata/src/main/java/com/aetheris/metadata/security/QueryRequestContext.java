package com.aetheris.metadata.security;

import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.http.HttpServletRequest;

/** 只从实时验签后的请求读取关联 ID；不接受同名原始 header 回退。 */
public final class QueryRequestContext {
    private QueryRequestContext() { }
    public static String requestId(HttpServletRequest request, AetherisIdentity identity) {
        if (request != null && identity != null
                && request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser user
                && java.util.Objects.equals(user.proof().uid(), identity.uid())
                && java.util.Objects.equals(user.proof().username(), identity.username())) {
            return user.proof().requestId();
        }
        return null;
    }
}
