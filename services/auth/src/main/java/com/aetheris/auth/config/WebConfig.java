package com.aetheris.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册内部端点拦截器。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final InternalEndpointInterceptor internalEndpointInterceptor;

    public WebConfig(InternalEndpointInterceptor internalEndpointInterceptor) {
        this.internalEndpointInterceptor = internalEndpointInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalEndpointInterceptor).addPathPatterns("/internal/**");
    }
}