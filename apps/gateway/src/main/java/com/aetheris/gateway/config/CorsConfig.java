package com.aetheris.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 跨域配置。
 *
 * <p>为什么用代码而不是 {@code spring.cloud.gateway.globalcors} 的 YAML 配置：
 * 实测该 YAML 配置在本版本下未生效——带任意 {@code Origin} 头的请求（包括显式放行的来源）
 * 一律被拒为 403，且直连网关与经 nginx 转发的结果一致。Gateway 的全局 CORS 属性对
 * 未匹配路由的请求存在已知缺陷，属性命名空间也在版本间迁移过。用 {@code CorsWebFilter}
 * Bean 的行为是确定的，不依赖具体版本的属性路径。
 *
 * <p>这个问题的表现极具误导性：{@code curl} 默认不发送 {@code Origin} 头，因此命令行
 * 一切正常；而浏览器即便同源请求也会带上 {@code Origin}，于是只在浏览器里登录失败。
 *
 * <p>过滤器顺序设为最高优先级：必须早于 {@code JwtAuthFilter}（order=-100），
 * 否则预检的 OPTIONS 请求会先被鉴权逻辑处理。
 */
@Configuration
public class CorsConfig {

    private final String allowedOrigins;

    public CorsConfig(@Value("${aetheris.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Credentialed cookies must use an explicit allow-list. Add a dev
        // origin through AETHERIS_CORS_ALLOWED_ORIGINS when Vite runs elsewhere.
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isBlank()).toList();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        configuration.setAllowedHeaders(List.of("*"));
        // Browser auth uses same-site HttpOnly cookies. Credentials must be
        // enabled for the Vite dev origin; production is served same-origin by
        // nginx and does not widen this allow-list.
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsWebFilter(source);
    }
}
