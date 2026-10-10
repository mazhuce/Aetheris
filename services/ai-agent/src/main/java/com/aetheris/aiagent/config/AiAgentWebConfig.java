package com.aetheris.aiagent.config;

import com.aetheris.aiagent.security.IdentityInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AiAgentWebConfig implements WebMvcConfigurer {
    private final IdentityInterceptor identityInterceptor;

    public AiAgentWebConfig(IdentityInterceptor identityInterceptor) {
        this.identityInterceptor = identityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor).addPathPatterns("/api/ai/**");
    }
}
