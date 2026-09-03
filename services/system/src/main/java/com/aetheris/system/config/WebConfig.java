package com.aetheris.system.config;

import com.aetheris.system.security.AdminEndpointInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册系统管理敏感端点的服务端权限门。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminEndpointInterceptor adminEndpointInterceptor;

    public WebConfig(AdminEndpointInterceptor adminEndpointInterceptor) {
        this.adminEndpointInterceptor = adminEndpointInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminEndpointInterceptor)
                .addPathPatterns("/api/system/audit-logs/**", "/api/system/permission-events/**");
    }
}