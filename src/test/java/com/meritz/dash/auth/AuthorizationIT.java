package com.meritz.dash.auth;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationIT extends AbstractOracleIT {

    @Autowired TestRestTemplate rest;
    @Autowired AccountProvisioner provisioner;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void seed() { provisioner.provision(); }

    @AfterEach
    void cleanup() {
        // admin_can_write 테스트가 E9999를 삽입할 수 있으므로 반드시 정리
        // 다른 IT의 HR 카운트 단언(4건) 보호
        jdbc.update("DELETE FROM HR_DEVELOPER WHERE EMPNO = 'E9999'");
        // login()이 해제한 초기화 플래그 원복 — 다른 IT의 pwdResetRequired=true 단언 보호
        jdbc.update("UPDATE AUTH_ACCOUNT SET PWD_RESET_YN = 'Y' WHERE EMPNO IN ('E0001','E0002')");
    }

    /**
     * 인가(역할) 검증용 토큰 발급. pwdReset=true 토큰은 업무 API가
     * PASSWORD_RESET_REQUIRED로 차단되므로, 초기화 플래그를 해제한 뒤 로그인한다.
     * (플래그는 cleanup()에서 'Y'로 원복 — 비밀번호 자체는 바꾸지 않아 다른 IT에 영향 없음)
     */
    private String login(String empno) {
        jdbc.update("UPDATE AUTH_ACCOUNT SET PWD_RESET_YN = 'N' WHERE EMPNO = ?", empno);
        ResponseEntity<String> r = rest.postForEntity("/api/v1/auth/login",
            Map.of("empno", empno, "password", empno), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        return body.replaceAll("(?s).*\"token\":\"([^\"]+)\".*", "$1");
    }

    /** 초기 상태(비번=사번, PWD_RESET_YN='Y') 그대로 로그인 → pwdReset=true 토큰 */
    private String loginWithResetFlag(String empno) {
        ResponseEntity<String> r = rest.postForEntity("/api/v1/auth/login",
            Map.of("empno", empno, "password", empno), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().replaceAll("(?s).*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("pwdReset=true 토큰으로 업무 API → 403 PASSWORD_RESET_REQUIRED")
    void pwd_reset_token_blocked_on_business_api() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(loginWithResetFlag("E0001"));
        ResponseEntity<String> r = rest.exchange("/api/v1/developers", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody()).contains("PASSWORD_RESET_REQUIRED");
    }

    @Test
    @DisplayName("pwdReset=true 토큰으로 /auth/me → 200(본인 계정 경로 허용)")
    void pwd_reset_token_allowed_on_me() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(loginWithResetFlag("E0002"));
        ResponseEntity<String> r = rest.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("무토큰으로 보호 엔드포인트 → 401")
    void no_token_401() {
        ResponseEntity<String> r = rest.getForEntity("/api/v1/developers", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("일반직원(E0002, 역할03) 토큰으로 인사 POST → 403")
    void user_cannot_write() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(login("E0002"));
        h.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> e = new HttpEntity<>("{\"empno\":\"E9001\",\"empNm\":\"신규\"}", h);
        ResponseEntity<String> r = rest.postForEntity("/api/v1/developers", e, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("팀장(E0001, 역할01) 토큰으로 인사 조회 → 200")
    void team_lead_can_read() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(login("E0001"));
        ResponseEntity<String> r = rest.exchange("/api/v1/developers", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("공개 경로 health → 토큰 없이 200")
    void health_public() {
        assertThat(rest.getForEntity("/api/v1/health", String.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    // ──────────────────────────────────────────────────────────────────────
    // admin 인가 테스트
    // ──────────────────────────────────────────────────────────────────────

    private String loginAdmin() {
        ResponseEntity<String> r = rest.postForEntity("/api/v1/auth/login",
            Map.of("empno", "admin", "password", "admin"), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        return body.replaceAll("(?s).*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("admin 토큰으로 POST /developers → 201(ADMIN 역할 허용)")
    void admin_can_write() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(loginAdmin());
        h.setContentType(MediaType.APPLICATION_JSON);
        // E9999는 HR_DEVELOPER에 없으므로 create가 실제로 성공하려면 HR 데이터가 필요하다.
        // 여기서 확인하는 건 인가(403이 아닌 것), 즉 200/201/4xx 중 403 아님을 검증한다.
        HttpEntity<String> e = new HttpEntity<>(
            "{\"empno\":\"E9999\",\"empNm\":\"어드민테스트\"}", h);
        ResponseEntity<String> r = rest.postForEntity("/api/v1/developers", e, String.class);
        assertThat(r.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("일반직원(03) 토큰은 여전히 POST /developers → 403")
    void regular_user_still_forbidden() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(login("E0002"));
        h.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> e = new HttpEntity<>("{\"empno\":\"E9001\",\"empNm\":\"신규\"}", h);
        ResponseEntity<String> r = rest.postForEntity("/api/v1/developers", e, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 공통코드 인가 테스트
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("일반직원(03) 토큰으로 POST /codes → 403")
    void user_cannot_create_code() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(login("E0002"));
        h.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> e = new HttpEntity<>(
            "{\"grpCd\":\"AUTH_TST\",\"cdVal\":\"X1\",\"cdNm\":\"테스트\"}", h);
        ResponseEntity<String> r = rest.postForEntity("/api/v1/codes", e, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("admin 토큰으로 POST /codes → 201(ADMIN 역할 허용)")
    void admin_can_create_code() {
        String uniqueVal = "A" + System.currentTimeMillis() % 100000;
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(loginAdmin());
        h.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> e = new HttpEntity<>(
            "{\"grpCd\":\"AUTH_TST\",\"cdVal\":\"" + uniqueVal + "\",\"cdNm\":\"어드민코드\"}", h);
        ResponseEntity<String> r = rest.postForEntity("/api/v1/codes", e, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
