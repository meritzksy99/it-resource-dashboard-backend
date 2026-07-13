package com.meritz.dash.auth;

import com.meritz.dash.config.GatewayAuthProperties;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayTokenVerifierTest {

    private static final String ISSUER = "https://sso.example.com/realms/it";
    private static final String AUDIENCE = "oauth2-proxy";

    private static final TestJwks JWKS = new TestJwks();

    private static final GatewayAuthProperties PROPS = new GatewayAuthProperties(
        true, "X-Auth-Request-Access-Token", "http://localhost:9999/jwks",
        ISSUER, AUDIENCE, Set.of("dev-user"));

    private final GatewayTokenVerifier verifier = new GatewayTokenVerifier(PROPS, JWKS.jwkSource());

    private JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .claim("preferred_username", "9320")
            .claim("groups", List.of("role:dev-user", "/it-part-a"))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }

    @Test
    @DisplayName("유효 토큰 → GatewayPrincipal(empno=9320, roles={dev-user})")
    void valid_token() {
        String token = JWKS.sign(validClaims().build());

        GatewayPrincipal principal = verifier.verify(token);

        assertThat(principal.empno()).isEqualTo("9320");
        assertThat(principal.gatewayRoles()).containsExactly("dev-user");
    }

    @Test
    @DisplayName("groups에서 role: 접두 항목만 추리고 접두 제거")
    void multiple_role_groups() {
        String token = JWKS.sign(validClaims()
            .claim("groups", List.of("role:dev-user", "role:dev-admin", "/it-part-a"))
            .build());

        assertThat(verifier.verify(token).gatewayRoles())
            .containsExactlyInAnyOrder("dev-user", "dev-admin");
    }

    @Test
    @DisplayName("groups 없음 → 빈 roles (검증 실패 아님)")
    void missing_groups_is_empty_roles() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .claim("preferred_username", "9320")
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();

        GatewayPrincipal principal = verifier.verify(JWKS.sign(claims));

        assertThat(principal.empno()).isEqualTo("9320");
        assertThat(principal.gatewayRoles()).isEmpty();
    }

    @Test
    @DisplayName("role: 항목이 없는 groups → 빈 roles")
    void groups_without_role_prefix_is_empty_roles() {
        String token = JWKS.sign(validClaims()
            .claim("groups", List.of("/it-part-a", "/it-part-b"))
            .build());

        assertThat(verifier.verify(token).gatewayRoles()).isEmpty();
    }

    @Test
    @DisplayName("다른 키로 서명(같은 kid) → UnauthorizedException")
    void wrong_signature() {
        TestJwks otherKey = new TestJwks();
        String forged = otherKey.sign(validClaims().build());

        assertThatThrownBy(() -> verifier.verify(forged))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("잘못된 iss → UnauthorizedException")
    void wrong_issuer() {
        String token = JWKS.sign(validClaims()
            .issuer("https://evil.example.com/realms/it").build());

        assertThatThrownBy(() -> verifier.verify(token))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("잘못된 aud → UnauthorizedException")
    void wrong_audience() {
        String token = JWKS.sign(validClaims()
            .audience("some-other-client").build());

        assertThatThrownBy(() -> verifier.verify(token))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("만료 토큰 → UnauthorizedException")
    void expired_token() {
        String token = JWKS.sign(validClaims()
            .expirationTime(Date.from(Instant.now().minusSeconds(300))).build());

        assertThatThrownBy(() -> verifier.verify(token))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("preferred_username 부재 → UnauthorizedException")
    void missing_preferred_username() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .claim("groups", List.of("role:dev-user"))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();

        assertThatThrownBy(() -> verifier.verify(JWKS.sign(claims)))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("파싱 불가 문자열 → UnauthorizedException")
    void unparsable_token() {
        assertThatThrownBy(() -> verifier.verify("not.a.jwt"))
            .isInstanceOf(UnauthorizedException.class);
    }
}
