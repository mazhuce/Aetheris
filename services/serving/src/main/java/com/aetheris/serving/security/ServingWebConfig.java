package com.aetheris.serving.security;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class ServingWebConfig implements WebMvcConfigurer {
    private final ServingIdentityInterceptor identityInterceptor;
    public ServingWebConfig(ServingIdentityInterceptor identityInterceptor){this.identityInterceptor=identityInterceptor;}
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(identityInterceptor).addPathPatterns("/api/**");
    }
}
