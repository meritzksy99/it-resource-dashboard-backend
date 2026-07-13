package com.meritz.dash.config;

import com.meritz.dash.auth.GatewayAuthInterceptor;
import com.meritz.dash.auth.GatewayTokenVerifier;
import com.meritz.dash.mapper.app.DeveloperMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final GatewayAuthProperties gatewayProps;
    private final DeveloperMapper developers;

    public WebConfig(GatewayAuthProperties gatewayProps, DeveloperMapper developers) {
        this.gatewayProps = gatewayProps;
        this.developers = developers;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // enabled=false(local dev 우회)면 원격 JWKS 검증기를 만들지 않는다(우회 시 verifier 미사용).
        GatewayTokenVerifier verifier =
            gatewayProps.enabled() ? new GatewayTokenVerifier(gatewayProps) : null;
        registry.addInterceptor(new GatewayAuthInterceptor(gatewayProps, verifier, developers))
                .addPathPatterns("/api/v1/**",
                        // springdoc 문서 경로 — 인터셉터가 ADMIN 전용으로 잠근다(보안팀 지적 대응).
                        // springdoc.api-docs.path / swagger-ui.path 설정 변경 시 인터셉터의
                        // DOC_PATH_PREFIXES 와 함께 수정할 것(현재는 기본 경로 사용).
                        "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                        "/swagger-ui", "/swagger-ui/**", "/swagger-ui.html")
                .excludePathPatterns("/api/v1/health");
    }
}
