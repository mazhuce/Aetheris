package com.aetheris.metadata.config;

import com.aetheris.metadata.security.IdentityInterceptor;
import com.aetheris.metadata.security.InternalTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册身份拦截器：所有 /api/metadata/** 业务端点强制有效网关身份；
 * /internal/**（服务间契约）不在此拦截范围。
 */
@Configuration
public class MetadataWebConfig implements WebMvcConfigurer {

    private final IdentityInterceptor identityInterceptor;
    private final InternalTokenInterceptor internalTokenInterceptor;

    public MetadataWebConfig(IdentityInterceptor identityInterceptor,
                             InternalTokenInterceptor internalTokenInterceptor) {
        this.identityInterceptor = identityInterceptor;
        this.internalTokenInterceptor = internalTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(identityInterceptor).addPathPatterns("/api/metadata/**");
        registry.addInterceptor(internalTokenInterceptor).addPathPatterns("/internal/**");
    }
}
