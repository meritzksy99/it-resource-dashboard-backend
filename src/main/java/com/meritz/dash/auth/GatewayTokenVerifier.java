package com.meritz.dash.auth;

import com.meritz.dash.config.GatewayAuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 게이트웨이(Keycloak/AD)가 발급한 액세스 토큰을 JWKS 공개키로 검증한다.
 * RS256 서명 + iss + aud + exp + preferred_username 필수. 실패는 전부 {@link UnauthorizedException}.
 */
public class GatewayTokenVerifier {

    private static final String CLAIM_USERNAME = "preferred_username";
    private static final String CLAIM_GROUPS = "groups";
    private static final String ROLE_PREFIX = "role:";

    private final ConfigurableJWTProcessor<SecurityContext> processor;

    /** 운영용 — properties의 jwksUrl에서 원격 JWKS를 가져온다(캐싱 포함). */
    public GatewayTokenVerifier(GatewayAuthProperties props) {
        this(props, remoteJwkSource(props.jwksUrl()));
    }

    /** 테스트/커스텀 JWKSource 주입용. */
    public GatewayTokenVerifier(GatewayAuthProperties props, JWKSource<SecurityContext> jwkSource) {
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
            props.audience(),
            new JWTClaimsSet.Builder().issuer(props.issuer()).build(),
            Set.of(CLAIM_USERNAME, "exp")));
        this.processor = p;
    }

    private static JWKSource<SecurityContext> remoteJwkSource(String jwksUrl) {
        try {
            return JWKSourceBuilder.create(URI.create(jwksUrl).toURL()).build();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalArgumentException("잘못된 JWKS URL: " + jwksUrl, e);
        }
    }

    /**
     * @return 검증된 토큰의 사번·게이트웨이 롤. groups가 없거나 role: 항목이 없으면 빈 Set(여기서는 실패 아님).
     * @throws UnauthorizedException 서명/iss/aud/만료/파싱불가/필수 클레임 부재 등 모든 검증 실패
     */
    public GatewayPrincipal verify(String token) {
        try {
            JWTClaimsSet claims = processor.process(token, null);
            return new GatewayPrincipal(claims.getStringClaim(CLAIM_USERNAME), extractRoles(claims));
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new UnauthorizedException("게이트웨이 토큰 검증 실패: " + e.getMessage());
        }
    }

    private static Set<String> extractRoles(JWTClaimsSet claims) throws ParseException {
        List<String> groups = claims.getStringListClaim(CLAIM_GROUPS);
        if (groups == null) {
            return Set.of();
        }
        return groups.stream()
            .filter(g -> g != null && g.startsWith(ROLE_PREFIX))
            .map(g -> g.substring(ROLE_PREFIX.length()))
            .collect(Collectors.toUnmodifiableSet());
    }
}
