package com.meritz.dash.resource;

import com.meritz.dash.auth.AccountProvisioner;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/resource/overtime RBAC 통합테스트 — HR_OVERTIME(엑셀 업로드 실적) 기반.
 * 실제 JWT 토큰으로 역할별 데이터 범위 제한을 검증한다.
 *
 * 픽스처 기준(V002/V013 HR_DEVELOPER 시드 + 본 테스트의 HR_OVERTIME 시드):
 *   E0002 — P01, role=03: 774분 야근 업로드됨
 *   E0003 — P02, role=03: 120분 야근 업로드됨
 *   E0001 — P01, role=01(팀장): 야근 없음
 *   T0001 — P01, role=02(업무리더, 테스트 전 임시 insert): 야근 없음
 * (부서코드는 V013에서 D101→2139 정규화될 수 있어 E0002의 실제 값을 조회해 사용)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OvertimeRbacIT extends AbstractOracleIT {

    @Autowired TestRestTemplate rest;
    @Autowired AccountProvisioner provisioner;
    @Autowired PasswordEncoder encoder;
    @Autowired @Qualifier("appDataSource") DataSource appDs;

    private static final String PERIOD = "202605";

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(appDs);
    }

    /** E0002의 실제 부서코드 (V013 정규화 여부와 무관하게 동작). */
    private String deptOfE0002() {
        return jdbc().queryForObject(
                "SELECT DEPT_CD FROM HR_DEVELOPER WHERE EMPNO = 'E0002'", String.class);
    }

    @BeforeEach
    void seedRbac() {
        String dept = deptOfE0002();
        // 1) 업무리더(role=02) 테스트용 직원 임시 insert — E0002와 같은 부서/파트(P01)
        int cnt = jdbc().queryForObject(
                "SELECT COUNT(*) FROM HR_DEVELOPER WHERE EMPNO = 'T0001'", Integer.class);
        if (cnt == 0) {
            jdbc().update(
                "INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD) " +
                "VALUES ('T0001', '이리더', ?, 'P01', '대리', '02', 'Y', '01')", dept);
        }
        // 2) 계정 프로비저닝 (없는 계정 생성)
        provisioner.provision();
        // 3) E0001~E0004 + T0001 비밀번호 리셋 (컨테이너 재사용 환경 대비)
        JdbcTemplate j = jdbc();
        for (String empno : new String[]{"E0001", "E0002", "E0003", "E0004", "T0001"}) {
            j.update(
                "UPDATE AUTH_ACCOUNT SET PASSWORD_HASH = ?, PWD_RESET_YN = 'N'," +
                "  UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = ? WHERE EMPNO = ?",
                encoder.encode(empno), empno, empno);
        }
        // 4) HR_OVERTIME 시드(멱등) — 야근 조회 원천: E0002(P01) 774분, E0003(P02) 120분
        j.update("DELETE FROM HR_OVERTIME WHERE PERIOD_YM = ?", PERIOD);
        j.update("INSERT INTO HR_OVERTIME (PERIOD_YM, EMPNO, OT_MINUTES, CREATED_BY) " +
                 "VALUES (?, 'E0002', 774, 'TEST')", PERIOD);
        j.update("INSERT INTO HR_OVERTIME (PERIOD_YM, EMPNO, OT_MINUTES, CREATED_BY) " +
                 "VALUES (?, 'E0003', 120, 'TEST')", PERIOD);
    }

    @AfterEach
    void cleanupRbac() {
        jdbc().update("DELETE FROM HR_OVERTIME WHERE PERIOD_YM = ?", PERIOD);
        jdbc().update("DELETE FROM HR_DEVELOPER WHERE EMPNO = 'T0001'");
        jdbc().update("DELETE FROM AUTH_ACCOUNT WHERE EMPNO = 'T0001'");
    }

    // ──────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────

    private String loginToken(String empno) {
        ResponseEntity<String> r = rest.postForEntity("/api/v1/auth/login",
                Map.of("empno", empno, "password", empno), String.class);
        assertThat(r.getStatusCode())
            .as("login empno=" + empno + " body=" + r.getBody())
            .isEqualTo(HttpStatus.OK);
        return r.getBody().replaceAll("(?s).*\"token\":\"([^\"]+)\".*", "$1");
    }

    private String loginAdmin() {
        ResponseEntity<String> r = rest.postForEntity("/api/v1/auth/login",
                Map.of("empno", "admin", "password", "admin"), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().replaceAll("(?s).*\"token\":\"([^\"]+)\".*", "$1");
    }

    private ResponseEntity<String> getOvertime(String token, String period) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(
                "/api/v1/resource/overtime?period=" + period,
                HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private ResponseEntity<String> getOvertimeWithDept(String token, String period, String dept) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(
                "/api/v1/resource/overtime?period=" + period + "&dept=" + dept,
                HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // 팀장(E0001, role=01) — 본인 부서 전체 (야근자 E0002·E0003)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("팀장(E0001): 본인 부서 야근자 E0002·E0003 포함 + 시간 기반 필드")
    void team_lead_sees_own_dept_only() {
        String token = loginToken("E0001");
        ResponseEntity<String> r = getOvertime(token, PERIOD);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        // 본인 부서 야근자가 포함되어야 한다
        assertThat(body).contains("E0002");
        assertThat(body).contains("E0003");
        // HR_OVERTIME 기반 시간 계약 — 774분=12.9h
        assertThat(body).contains("\"otMinutes\":774");
        assertThat(body).contains("\"overtimeHours\":12.9");
        assertThat(body).contains("avgOvertimeHours");
    }

    @Test
    @DisplayName("팀장(E0001): dept=타부서 파라미터 무시 — 본인 부서 야근자가 여전히 반환")
    void team_lead_ignores_client_dept_param() {
        String token = loginToken("E0001");
        // D199는 존재하지 않는 타부서 파라미터
        ResponseEntity<String> r = getOvertimeWithDept(token, PERIOD, "D199");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        // 팀장은 클라이언트 파라미터를 무시하고 자신의 부서 기준으로 조회해야 한다
        // D199 파라미터가 적용됐다면 E0002·E0003이 사라져야 하지만, 무시하면 나와야 한다
        assertThat(body).contains("E0002");
        assertThat(body).contains("E0003");
    }

    // ──────────────────────────────────────────────────────────────────
    // 업무리더(T0001, part=P01, role=02) — P01 파트만 (야근자 E0002)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("업무리더(T0001, P01): 본인 파트(P01) E0002 포함 · 타파트(P02) E0003 제외")
    void biz_leader_sees_own_part_only() {
        String token = loginToken("T0001");
        ResponseEntity<String> r = getOvertime(token, PERIOD);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        // P01 소속 야근자 E0002는 있어야 한다
        assertThat(body).contains("E0002");
        // P02 소속 야근자 E0003은 없어야 한다
        assertThat(body).doesNotContain("E0003");
        assertThat(body).contains("avgOvertimeHours");
    }

    @Test
    @DisplayName("업무리더(T0001): dept 파라미터 무시 — 본인 파트(P01)만 반환, P02 E0003 제외")
    void biz_leader_ignores_client_dept_param() {
        String token = loginToken("T0001");
        // D199 파라미터를 줘도 무시되어 P01 기준 결과가 나와야 한다
        ResponseEntity<String> r = getOvertimeWithDept(token, PERIOD, "D199");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).contains("E0002");
        assertThat(body).doesNotContain("E0003");
    }

    // ──────────────────────────────────────────────────────────────────
    // 일반직원(E0002, role=03) — 본인만
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("일반직원(E0002): 본인(E0002) contains · 같은 파트 타인(E0001·T0001) doesNotContain")
    void regular_user_sees_only_self() {
        String token = loginToken("E0002");
        ResponseEntity<String> r = getOvertime(token, PERIOD);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        // 본인은 포함
        assertThat(body).contains("E0002");
        // 같은 파트(P01) 타인은 없어야 한다
        assertThat(body).doesNotContain("E0001");
        assertThat(body).doesNotContain("T0001");
        // 타 파트(P02) 타인도 없어야 한다
        assertThat(body).doesNotContain("E0003");
        assertThat(body).doesNotContain("E0004");
        assertThat(body).contains("avgOvertimeHours");
    }

    @Test
    @DisplayName("일반직원(E0002): dept=타부서 파라미터 줘도 본인 것만 반환")
    void regular_user_ignores_dept_param() {
        String token = loginToken("E0002");
        // 타부서 파라미터를 줘도 무시되어 본인(E0002)만 나와야 한다
        ResponseEntity<String> r = getOvertimeWithDept(token, PERIOD, "D199");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).contains("E0002");
        assertThat(body).doesNotContain("E0003");
    }

    // ──────────────────────────────────────────────────────────────────
    // ADMIN — 전체 (여러 부서 야근자 포함)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: 전체 — 야근자 E0002·E0003 모두 포함")
    void admin_sees_all() {
        String token = loginAdmin();
        ResponseEntity<String> r = getOvertime(token, PERIOD);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        // 여러 부서 야근자 모두 포함
        assertThat(body).contains("E0002");
        assertThat(body).contains("E0003");
        assertThat(body).contains("avgOvertimeHours");
    }

    @Test
    @DisplayName("ADMIN: dept 파라미터 전달 → 해당 부서 야근자 E0002·E0003 포함")
    void admin_dept_param_respected() {
        String token = loginAdmin();
        ResponseEntity<String> r = getOvertimeWithDept(token, PERIOD, deptOfE0002());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = r.getBody();
        assertThat(body).contains("E0002");
        assertThat(body).contains("E0003");
        assertThat(body).contains("avgOvertimeHours");
    }
}
