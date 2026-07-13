package com.meritz.dash.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * 테스트용 RSA 키쌍/JWKS/RS256 서명 헬퍼.
 * GatewayTokenVerifierTest 외에 Task 3(인터셉터)·Task 6(IT)에서도 재사용한다.
 */
public final class TestJwks {

    /** 모든 인스턴스가 같은 kid를 쓰므로 "다른 키, 같은 kid"로 위조 서명 시나리오를 만들 수 있다. */
    public static final String KEY_ID = "test-key";

    private final RSAKey rsaKey;

    public TestJwks() {
        try {
            this.rsaKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("테스트 RSA 키 생성 실패", e);
        }
    }

    /** 공개키만 담은 정적 JWKSource — verifier 테스트 생성자에 주입. */
    public JWKSource<SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey.toPublicJWK()));
    }

    /** 공개키 JWKS JSON — IT에서 가짜 JWKS 엔드포인트(WireMock 등) 응답으로 사용. */
    public String publicJwksJson() {
        return new JWKSet(rsaKey.toPublicJWK()).toString();
    }

    /** 이 키의 개인키로 RS256 서명한 compact JWT를 만든다. */
    public String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("테스트 JWT 서명 실패", e);
        }
    }
}
