package com.meritz.dash.auth;

import com.meritz.dash.mapper.app.AuthAccountMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AccountProvisionerIT extends AbstractOracleIT {

    @Autowired AccountProvisioner provisioner;
    @Autowired AuthAccountMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired @Qualifier("appDataSource") DataSource appDataSource;

    /** 다른 IT에서 비밀번호/pwdResetYn을 변경했을 수 있으므로 초기 상태로 복원 */
    @BeforeEach
    void resetToInitial() {
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
    @DisplayName("프로비저너: 재직자(E0001~E0004) 계정 생성, 초기비번=사번, 멱등")
    void provisions() {
        provisioner.provision();
        AuthAccount a = mapper.findByEmpno("E0002");
        assertThat(a).isNotNull();
        assertThat(a.pwdResetYn()).isEqualTo("Y");
        assertThat(encoder.matches("E0002", a.passwordHash())).isTrue(); // 초기비번=사번

        int needBefore = mapper.findEmpnosNeedingAccount().size();
        provisioner.provision();                 // 재실행 멱등
        assertThat(mapper.findEmpnosNeedingAccount().size()).isEqualTo(needBefore);
    }
}
