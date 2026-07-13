package com.meritz.dash.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc/Swagger 전역 설정.
 * <p>
 * 인증은 사내 게이트웨이(Nginx + Keycloak/AD)가 전담하며, 게이트웨이가 주입하는
 * {@code X-Access-Token} 헤더를 {@code GatewayAuthInterceptor} 가 검증한다(자체 로그인 API 없음).
 * 아래 apiKey(header) 보안 스킴을 선언해 Swagger UI 상단에 <b>Authorize</b> 버튼이 뜨게 한다 —
 * 토큰을 한 번 붙이면 모든 "Try it out" 호출에 {@code X-Access-Token: ...} 이 자동 첨부된다.
 * (전역 SecurityRequirement 는 문서/토큰첨부용이며, 공개 엔드포인트(/health 등)는 토큰이 있어도 무시된다.
 *  게이트웨이 없는 local 실행은 {@code app.gateway.enabled=false} 로 헤더에 사번만 넣는 dev 우회 사용.)
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
                        + "인증은 사내 게이트웨이(Keycloak/AD)가 발급한 X-Access-Token 헤더로 한다 — "
                        + "게이트웨이 경유 호출이면 자동 첨부되고, 직접 테스트 시 우측 상단 Authorize 에 토큰을 붙인다. "
                        + "(local에서 게이트웨이 우회(enabled=false) 시 토큰 대신 사번을 입력)"
        ),
        security = @SecurityRequirement(name = "gateway-token")
)
@SecurityScheme(
        name = "gateway-token",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "X-Access-Token",
        description = "게이트웨이(Keycloak/AD) 액세스 토큰(RS256 JWT). 게이트웨이가 X-Access-Token 헤더로 주입한다. "
                + "local 우회(app.gateway.enabled=false) 시에는 HR 사번을 그대로 입력."
)
public class OpenApiConfig {
}
