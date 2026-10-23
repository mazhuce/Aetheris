package com.aetheris.auth.security;

import com.aetheris.auth.config.AuthV2SecurityConfiguration;
import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/** Public auth routes are explicitly excluded by WebConfig; all other identities are signed. */
@Component
public class AuthIdentityInterceptor implements HandlerInterceptor {
    public static final String USERNAME_ATTRIBUTE = "aetheris.username";
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (AuthV2SecurityConfiguration.isPublic(request.getMethod(), request.getRequestURI())) return true;
        if (!(request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser user)) return reject(response, 401);
        request.setAttribute(USERNAME_ATTRIBUTE, user.proof().username());
        return true;
    }

    private boolean reject(HttpServletResponse response, int status) throws IOException {
        response.sendError(status, "缺少有效的认证身份");
        return false;
    }
}
