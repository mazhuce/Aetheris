package com.aetheris.quality.config;

import com.aetheris.quality.security.GatewayIdentityInterceptor;
import com.aetheris.quality.security.InternalTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityWebConfig implements WebMvcConfigurer {
    private final GatewayIdentityInterceptor identityInterceptor;
    private final InternalTokenInterceptor internalTokenInterceptor;

    public SecurityWebConfig(GatewayIdentityInterceptor identityInterceptor,
                             InternalTokenInterceptor internalTokenInterceptor) {
        this.identityInterceptor = identityInterceptor;
        this.internalTokenInterceptor = internalTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(internalTokenInterceptor).addPathPatterns("/internal/**");
    }
}
