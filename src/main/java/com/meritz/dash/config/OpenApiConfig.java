package com.meritz.dash.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc/Swagger 전역 설정.
 * <p>
 * bearer-JWT 보안 스킴을 선언해 Swagger UI 상단에 <b>Authorize</b> 버튼이 뜨게 한다.
 * 토큰을 한 번 붙이면 모든 "Try it out" 호출에 {@code Authorization: Bearer ...} 가 자동 첨부된다.
 * (실제 인증 강제는 {@code JwtAuthInterceptor} 가 담당 — /auth/login, /health 는 무인증 허용.
 *  전역 SecurityRequirement 는 문서/토큰첨부용이며, 공개 엔드포인트는 토큰이 있어도 무시된다.)
 * <p>
 * 조회(GET)는 역할 제한 없이 인증된 모든 사용자(업무리더·팀장·일반직원)가 호출 가능하고,
 * 쓰기(등록/수정/삭제·집계 실행)만 팀장·ADMIN 으로 제한된다.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "IT 개발팀 리소스 현황 대시보드 API",
                version = "v1",
                description = "개발량 추이·리소스(M/M) 가동률·파트별 SR 요약 대시보드 백엔드. "
                        + "우측 상단 Authorize 에 로그인(/api/v1/auth/login) 응답의 data.token 을 붙이면 조회 API를 바로 테스트할 수 있다."
        ),
        security = @SecurityRequirement(name = "bearer-jwt")
)
@SecurityScheme(
        name = "bearer-jwt",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT 액세스 토큰. /api/v1/auth/login 응답의 data.token 값을 입력(‘Bearer ’ 접두어 없이)."
)
public class OpenApiConfig {
}
