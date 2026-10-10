package com.aetheris.system.config;

import com.aetheris.system.security.AdminEndpointInterceptor;
import com.aetheris.system.security.SystemIdentityInterceptor;
import com.aetheris.system.security.InternalTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册系统管理敏感端点的服务端权限门。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminEndpointInterceptor adminEndpointInterceptor;
    private final SystemIdentityInterceptor systemIdentityInterceptor;
    private final InternalTokenInterceptor internalTokenInterceptor;

    public WebConfig(AdminEndpointInterceptor adminEndpointInterceptor,
                     SystemIdentityInterceptor systemIdentityInterceptor,
                     InternalTokenInterceptor internalTokenInterceptor) {
        this.adminEndpointInterceptor = adminEndpointInterceptor;
        this.systemIdentityInterceptor = systemIdentityInterceptor;
        this.internalTokenInterceptor = internalTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminEndpointInterceptor)
                .addPathPatterns("/api/system/audit-logs/**", "/api/system/permission-events/**");
        registry.addInterceptor(systemIdentityInterceptor)
                .addPathPatterns("/api/system/activity", "/api/system/alerts/**",
                        "/api/system/notifications/**");
        registry.addInterceptor(internalTokenInterceptor)
                .addPathPatterns("/internal/**");
    }
}
