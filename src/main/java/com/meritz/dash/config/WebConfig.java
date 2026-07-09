package com.meritz.dash.config;

import com.meritz.dash.auth.JwtAuthInterceptor;
import com.meritz.dash.auth.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtService jwt;
    public WebConfig(JwtService jwt) { this.jwt = jwt; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtAuthInterceptor(jwt))
                .addPathPatterns("/api/v1/**", "/api/v2/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v2/auth/login", "/api/v1/health");
    }
}
