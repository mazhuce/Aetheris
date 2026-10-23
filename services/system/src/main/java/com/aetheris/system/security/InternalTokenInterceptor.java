package com.aetheris.system.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;

/** Retired, unregistered Java compatibility shell. A shared token never authorizes a request. */
@Deprecated
public class InternalTokenInterceptor implements HandlerInterceptor {
    public InternalTokenInterceptor(String ignoredToken) { }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws IOException {
        response.setStatus(401);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"Legacy internal authentication rejected\"}");return false;
    }
}
