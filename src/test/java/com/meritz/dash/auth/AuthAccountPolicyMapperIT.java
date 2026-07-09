package com.meritz.dash.auth;

import com.meritz.dash.support.AbstractOracleIT;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthAccountPolicyMapperIT extends AbstractOracleIT {

    /** AccountProvisioner(ApplicationRunner) 시드 계정 — V002 재직자 */
    private static final String EMPNO = "E0002";

    @Autowired AuthAccountMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired @Qualifier("appDataSource") DataSource appDataSource;

    /**
     * 컨테이너는 모든 IT 클래스가 공유(싱글톤 패턴)하고, 다른 IT(AuthorizationIT 등)는
     * E0002가 초기 상태(비번=사번, 정상, 실패 0회)라고 가정하므로 매 테스트 후 복원한다.
     */
    @AfterEach
    void restoreInitialState() {
        new JdbcTemplate(appDataSource).update(
            "UPDATE AUTH_ACCOUNT SET PASSWORD_HASH = ?, PWD_RESET_YN = 'Y', FAIL_CNT = 0," +
            "  STATUS_CD = '00', PREV_PASSWORD_HASH = NULL, PASSWORD_CHANGED_AT = SYSTIMESTAMP," +
            "  UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = ? WHERE EMPNO = ?",
            encoder.encode(EMPNO), EMPNO, EMPNO);
    }

    @Test
    void increment_and_lock_and_unlock_cycle() {
        String empno = EMPNO;
        int before = mapper.findByEmpno(empno).failCnt();
        mapper.incrementFail(empno);
        assertThat(mapper.findByEmpno(empno).failCnt()).isEqualTo(before + 1);

        mapper.lockAccount(empno);
        assertThat(mapper.findByEmpno(empno).statusCd()).isEqualTo("01");

        mapper.unlockAccount(empno);
        AuthAccount acc = mapper.findByEmpno(empno);
        assertThat(acc.statusCd()).isEqualTo("00");
        assertThat(acc.failCnt()).isZero();
    }

    @Test
    void change_password_with_history_moves_prev_hash() {
        String empno = EMPNO;
        mapper.changePasswordWithHistory(empno, "NEWHASH", "OLDHASH");
        AuthAccount acc = mapper.findByEmpno(empno);
        assertThat(acc.passwordHash()).isEqualTo("NEWHASH");
        assertThat(acc.prevPasswordHash()).isEqualTo("OLDHASH");
        assertThat(acc.pwdResetYn()).isEqualTo("N");
    }

    @Test
    void reset_to_default_forces_reset_and_clears_prev() {
        String empno = EMPNO;
        mapper.resetToDefault(empno, "DEFHASH");
        AuthAccount acc = mapper.findByEmpno(empno);
        assertThat(acc.passwordHash()).isEqualTo("DEFHASH");
        assertThat(acc.pwdResetYn()).isEqualTo("Y");
        assertThat(acc.statusCd()).isEqualTo("00");
        assertThat(acc.prevPasswordHash()).isNull();
    }

    /** 브리프 외 추가: markDormant/loginSuccess도 락 인터페이스이므로 커버 */
    @Test
    void mark_dormant_and_login_success_reset_fail_cnt() {
        mapper.markDormant(EMPNO);
        assertThat(mapper.findByEmpno(EMPNO).statusCd()).isEqualTo("02");

        mapper.incrementFail(EMPNO);
        mapper.loginSuccess(EMPNO);
        AuthAccount acc = mapper.findByEmpno(EMPNO);
        assertThat(acc.failCnt()).isZero();
        assertThat(acc.lastLoginAt()).isNotNull();
    }

    @Test
    void find_all_for_admin_returns_rows_with_name() {
        List<AuthAccountMapper.AdminRow> rows = mapper.findAllForAdmin();
        assertThat(rows).isNotEmpty();
        assertThat(rows).anyMatch(r -> r.empno().equals(EMPNO) && r.name() != null);
    }
}
