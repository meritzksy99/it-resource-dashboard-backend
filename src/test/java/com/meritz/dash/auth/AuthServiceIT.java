package com.meritz.dash.auth;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;

class AuthServiceIT extends AbstractOracleIT {

    @Autowired AuthService authService;
    @Autowired AccountProvisioner provisioner;
    @Autowired PasswordEncoder encoder;
    @Autowired @Qualifier("appDataSource") DataSource appDataSource;

    /**
     * 각 테스트 전 계정 상태를 초기 상태(비번=사번, PWD_RESET_YN='Y')로 리셋.
     * 공유 Oracle 컨테이너 재사용 환경에서 이전 실행 결과가 남아있을 수 있다.
     */
    @BeforeEach
    void resetAccounts() {
        provisioner.provision(); // 계정 없으면 생성
        JdbcTemplate jdbc = new JdbcTemplate(appDataSource);
        for (String empno : new String[]{"E0001", "E0002", "E0003", "E0004"}) {
            String hash = encoder.encode(empno);
            jdbc.update(
                "UPDATE AUTH_ACCOUNT SET PASSWORD_HASH = ?, PWD_RESET_YN = 'Y'," +
                "  UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = ? WHERE EMPNO = ?",
                hash, empno, empno);
        }
    }

    @Test
    @DisplayName("로그인 성공: 초기비번=사번 → pwdResetRequired=true, 역할 팀장(E0001)")
    void login_ok_team_lead() {
        LoginResult r = authService.login(new LoginRequest("E0001", "E0001"));
        assertThat(r.token()).isNotBlank();
        assertThat(r.role()).isEqualTo("01");          // E0001 ROLE_CD=01 팀장
        assertThat(r.pwdResetRequired()).isTrue();
    }

    @Test
    @DisplayName("로그인 실패: 틀린 비번 → UnauthorizedException")
    void login_bad_password() {
        assertThatThrownBy(() -> authService.login(new LoginRequest("E0002", "wrong")))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("비번 변경: old 검증 후 변경 + pwdReset 해제")
    void change_password() {
        authService.changePassword("E0002", new ChangePasswordRequest("E0002", "newpass12"));
        LoginResult r = authService.login(new LoginRequest("E0002", "newpass12"));
        assertThat(r.pwdResetRequired()).isFalse();
    }

    @Test
    @DisplayName("비번 정책: 8자 미만 → IllegalArgumentException")
    void change_password_too_short() {
        assertThatThrownBy(() -> authService.changePassword("E0003", new ChangePasswordRequest("E0003", "short")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // admin 계정 테스트 (HR/AUTH_ACCOUNT 조회 없이 토큰 발급)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("admin/admin 로그인 → role=ADMIN, pwdResetRequired=false")
    void admin_login_ok() {
        LoginResult r = authService.login(new LoginRequest("admin", "admin"));
        assertThat(r.token()).isNotBlank();
        assertThat(r.empno()).isEqualTo("admin");
        assertThat(r.role()).isEqualTo("ADMIN");
        assertThat(r.roleName()).isEqualTo("관리자");
        assertThat(r.name()).isEqualTo("관리자");
        assertThat(r.pwdResetRequired()).isFalse();
    }

    @Test
    @DisplayName("admin 틀린 비번 → UnauthorizedException")
    void admin_wrong_password() {
        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "wrong")))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("admin me() → role=ADMIN, HR 조회 없이 반환")
    void admin_me() {
        MeResult r = authService.me("admin");
        assertThat(r.empno()).isEqualTo("admin");
        assertThat(r.role()).isEqualTo("ADMIN");
        assertThat(r.roleName()).isEqualTo("관리자");
        assertThat(r.name()).isEqualTo("관리자");
        assertThat(r.partCd()).isNull();
        assertThat(r.pwdResetRequired()).isFalse();
    }
}
