package com.aetheris.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.aetheris.auth.security.AuthIdentityInterceptor;

/** API adapter consumes only the trusted identity installed by the v2 filters. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthIdentityInterceptor identityInterceptor;

    public WebConfig(AuthIdentityInterceptor identityInterceptor) {
        this.identityInterceptor = identityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor).addPathPatterns("/api/auth/**");
    }
}
