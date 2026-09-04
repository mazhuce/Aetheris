package com.aetheris.serving.security;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;
@Configuration
public class ServingWebConfig implements WebMvcConfigurer {
    private final IdentityVerifier verifier; public ServingWebConfig(IdentityVerifier verifier){this.verifier=verifier;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){public boolean preHandle(jakarta.servlet.http.HttpServletRequest req,HttpServletResponse res,Object handler)throws IOException{if(!verifier.configured()){res.sendError(503,"serving-service 未配置内部令牌");return false;}if(verifier.verify(req)==null){res.sendError(401,"缺少有效的网关身份签名");return false;}return true;}}).addPathPatterns("/api/**");}
}
