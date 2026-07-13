package com.meritz.dash.support;

import com.meritz.dash.auth.TestJwks;
import com.nimbusds.jwt.JWTClaimsSet;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 게이트웨이(X-Access-Token) 인증 경로를 실제로 태우는 HTTP 통합테스트 베이스.
 *
 * <ul>
 *   <li>테스트 RSA 키({@link TestJwks})의 공개키 JWKS를 JDK 내장 HttpServer로 서빙하고,
 *       {@code app.gateway.jwks-url}을 그 주소로 지정 → 운영과 동일한 "원격 JWKS 조회" 경로 검증.</li>
 *   <li>{@link #token(String)}으로 서명한 토큰을 {@code X-Access-Token} 헤더에 실어 호출하면
 *       실제 {@code GatewayAuthInterceptor} → HR_DEVELOPER 조회 → {@code @Auth} 검사를 전부 통과한다(목 없음).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractGatewayIT extends AbstractOracleIT {

    protected static final String TOKEN_HEADER = "X-Access-Token";
    protected static final String ISSUER = "http://localhost/realms/it-test";
    protected static final String AUDIENCE = "oauth2-proxy";
    /** 게이트웨이 대분류 role — 아래 gatewayProps()가 app.gateway.allowed-roles 프로퍼티를 이 값으로 오버라이드한다. */
    protected static final String ALLOWED_GATEWAY_ROLE = "dev-user";

    protected static final TestJwks JWKS = new TestJwks();
    private static final HttpServer JWKS_SERVER;

    static {
        try {
            byte[] body = JWKS.publicJwksJson().getBytes(StandardCharsets.UTF_8);
            JWKS_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS_SERVER.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            JWKS_SERVER.start();
        } catch (IOException e) {
            throw new IllegalStateException("테스트 JWKS 서버 기동 실패", e);
        }
    }

    @DynamicPropertySource
    static void gatewayProps(DynamicPropertyRegistry r) {
        r.add("app.gateway.enabled", () -> "true");
        r.add("app.gateway.jwks-url",
                () -> "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks");
        r.add("app.gateway.issuer", () -> ISSUER);
        r.add("app.gateway.audience", () -> AUDIENCE);
        r.add("app.gateway.allowed-roles", () -> ALLOWED_GATEWAY_ROLE);
    }

    /** 허용 게이트웨이 role(dev-user)로 서명한 유효 토큰. */
    protected static String token(String empno) {
        return token(empno, ALLOWED_GATEWAY_ROLE);
    }

    /** 지정 게이트웨이 role로 서명한 유효 토큰(허용목록 밖 role 시나리오용). */
    protected static String token(String empno, String gatewayRole) {
        return JWKS.sign(claims(empno, gatewayRole).build());
    }

    /** iss/aud/exp/preferred_username/groups 를 채운 클레임 빌더 — 만료 등 변형은 여기서 덮어쓴다. */
    protected static JWTClaimsSet.Builder claims(String empno, String gatewayRole) {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("preferred_username", empno)
                .claim("groups", List.of("role:" + gatewayRole));
    }

    /** X-Access-Token 헤더만 실은 GET용 엔티티. */
    protected static HttpEntity<Void> authEntity(String empno) {
        return new HttpEntity<>(authHeaders(empno));
    }

    protected static HttpHeaders authHeaders(String empno) {
        return headersWithToken(token(empno));
    }

    protected static HttpHeaders headersWithToken(String tokenValue) {
        HttpHeaders h = new HttpHeaders();
        h.set(TOKEN_HEADER, tokenValue);
        return h;
    }
}
