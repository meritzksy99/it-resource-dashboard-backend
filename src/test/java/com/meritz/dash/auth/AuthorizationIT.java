package com.meritz.dash.auth;

import com.meritz.dash.support.AbstractGatewayIT;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인가(401/403/200) 통합테스트 — 게이트웨이(X-Access-Token) 방식.
 * 테스트 RSA(TestJwks)로 서명한 토큰이 실제 GatewayAuthInterceptor(JWKS 원격 조회 포함)
 * → HR_DEVELOPER 조회 → @Auth 검사를 통과하는지 검증한다(목 없음).
 *
 * HR 픽스처(V002/V013): E0001 팀장(01) · E0002 일반직원(03).
 * ADMIN은 HR 시드에 없으므로 테스트 전 임시 insert(T9001)로 만든다.
 */
class AuthorizationIT extends AbstractGatewayIT {

    /** ADMIN 역할 검증용 임시 HR 사번 */
    private static final String ADMIN_EMPNO = "T9001";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedAdminRow() {
        // 과거 실행이 남겼을 수 있는 테스트 코드 그룹 잔여행 선제 정리(재사용 컨테이너 대비)
        jdbc.update("DELETE FROM CD_COMMON WHERE GRP_CD = 'AUTH_TST'");
        int cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM HR_DEVELOPER WHERE EMPNO = ?", Integer.class, ADMIN_EMPNO);
        if (cnt == 0) {
            jdbc.update(
                "INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD) " +
                "VALUES (?, '관리자', 'D101', 'P01', '부장', 'ADMIN', 'N', '01')", ADMIN_EMPNO);
        }
    }

    @AfterEach
    void cleanup() {
        // admin_can_write 가 E9999를 실제 등록하므로 반드시 정리(다른 IT의 HR 단언 보호)
        jdbc.update("DELETE FROM HR_DEVELOPER WHERE EMPNO IN ('E9999', ?)", ADMIN_EMPNO);
        // admin_can_create_code 가 CD_COMMON에 삽입한 테스트 그룹 행 정리(재사용 컨테이너 누적/유니크 충돌 방지)
        jdbc.update("DELETE FROM CD_COMMON WHERE GRP_CD = 'AUTH_TST'");
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> postJson(String path, String json, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }

    // ── 401: 토큰 없음/무효 ────────────────────────────────────────────

    @Test
    @DisplayName("무토큰으로 보호 엔드포인트 → 401")
    void no_token_401() {
        ResponseEntity<String> r = rest.getForEntity("/api/v1/developers", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("다른 키로 서명한 위조 토큰(같은 kid) → 401")
    void forged_token_401() {
        TestJwks otherKey = new TestJwks();
        String forged = otherKey.sign(claims("E0001", ALLOWED_GATEWAY_ROLE).build());
        ResponseEntity<String> r = get("/api/v1/developers", headersWithToken(forged));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("만료된 토큰 → 401")
    void expired_token_401() {
        JWTClaimsSet expired = claims("E0001", ALLOWED_GATEWAY_ROLE)
                .expirationTime(Date.from(Instant.now().minusSeconds(600)))
                .build();
        ResponseEntity<String> r = get("/api/v1/developers", headersWithToken(JWKS.sign(expired)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 403: 게이트웨이 role 불허 / HR 미등록 ─────────────────────────

    @Test
    @DisplayName("허용목록 밖 게이트웨이 role(infra-user) 토큰 → 403")
    void gateway_role_not_allowed_403() {
        ResponseEntity<String> r = get("/api/v1/developers",
                headersWithToken(token("E0001", "infra-user")));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("유효 토큰이지만 HR_DEVELOPER 미등록 사번 → 403 (fail-closed)")
    void hr_missing_empno_403() {
        ResponseEntity<String> r = get("/api/v1/developers", authHeaders("Z9999"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 역할별 200/403 ────────────────────────────────────────────────

    @Test
    @DisplayName("팀장(E0001, 역할01) 토큰으로 인사 조회 → 200")
    void team_lead_can_read() {
        ResponseEntity<String> r = get("/api/v1/developers", authHeaders("E0001"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("일반직원(E0002, 역할03) 토큰으로 /auth/me → 200 (본인 정보)")
    void me_returns_current_user() {
        ResponseEntity<String> r = get("/api/v1/auth/me", authHeaders("E0002"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"empno\":\"E0002\"");
    }

    @Test
    @DisplayName("일반직원(E0002, 역할03) 토큰으로 인사 POST → 403")
    void user_cannot_write() {
        ResponseEntity<String> r = postJson("/api/v1/developers",
                "{\"empno\":\"E9001\",\"empNm\":\"신규\"}", authHeaders("E0002"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("일반직원(E0002) 토큰으로 POST /codes → 403")
    void user_cannot_create_code() {
        ResponseEntity<String> r = postJson("/api/v1/codes",
                "{\"grpCd\":\"AUTH_TST\",\"cdVal\":\"X1\",\"cdNm\":\"테스트\"}", authHeaders("E0002"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── ADMIN (HR ROLE_CD='ADMIN') ────────────────────────────────────

    @Test
    @DisplayName("ADMIN(HR ROLE_CD=ADMIN) 토큰으로 POST /developers → 201")
    void admin_can_write() {
        ResponseEntity<String> r = postJson("/api/v1/developers",
                "{\"empno\":\"E9999\",\"empNm\":\"어드민테스트\"}", authHeaders(ADMIN_EMPNO));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("ADMIN 토큰으로 POST /codes → 201")
    void admin_can_create_code() {
        // cdVal 고정값 사용 — AUTH_TST 그룹 전체를 cleanup()에서 DELETE 하므로 유니크 충돌 없음
        ResponseEntity<String> r = postJson("/api/v1/codes",
                "{\"grpCd\":\"AUTH_TST\",\"cdVal\":\"A1\",\"cdNm\":\"어드민코드\"}",
                authHeaders(ADMIN_EMPNO));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── springdoc 문서 경로(/v3/api-docs, /swagger-ui) — ADMIN 전용 ──────

    @Test
    @DisplayName("무토큰으로 /v3/api-docs → 401")
    void api_docs_no_token_401() {
        ResponseEntity<String> r = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("일반직원(E0002) 토큰으로 /v3/api-docs → 403")
    void api_docs_non_admin_403() {
        ResponseEntity<String> r = get("/v3/api-docs", authHeaders("E0002"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ADMIN 토큰으로 /v3/api-docs → 200 + OpenAPI JSON")
    void api_docs_admin_200() {
        ResponseEntity<String> r = get("/v3/api-docs", authHeaders(ADMIN_EMPNO));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"openapi\"");
    }

    @Test
    @DisplayName("무토큰으로 /swagger-ui/index.html → 401")
    void swagger_ui_no_token_401() {
        ResponseEntity<String> r = rest.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("ADMIN 토큰으로 /swagger-ui/index.html → 200")
    void swagger_ui_admin_200() {
        ResponseEntity<String> r = get("/swagger-ui/index.html", authHeaders(ADMIN_EMPNO));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── 공개 경로 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("공개 경로 health → 토큰 없이 200")
    void health_public() {
        assertThat(rest.getForEntity("/api/v1/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
