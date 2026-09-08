package com.aetheris.governance.config;

import com.aetheris.governance.security.GatewayIdentityInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityWebConfig implements WebMvcConfigurer {
    private final GatewayIdentityInterceptor identityInterceptor;

    public SecurityWebConfig(GatewayIdentityInterceptor identityInterceptor) {
        this.identityInterceptor = identityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor).addPathPatterns("/api/**");
    }
}
