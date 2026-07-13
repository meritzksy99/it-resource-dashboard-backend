package com.meritz.dash.config;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationIT extends AbstractOracleIT {

    @Autowired JdbcTemplate jdbc; // @Primary appDataSource 사용

    @Test
    @DisplayName("전체 마이그레이션 적용 후 SR_TPCD 7건, 인력 18건(V002 4 + V013 14)")
    void migration_applied() {
        Integer codes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM CD_COMMON WHERE GRP_CD='SR_TPCD'", Integer.class);
        Integer devs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM HR_DEVELOPER", Integer.class);
        assertThat(codes).isEqualTo(7);
        // V002 기본 4명(E0001~E0004) + V013 추가 14명(AI솔루션팀 8 + 개발팀 6) = 18명
        assertThat(devs).isEqualTo(18);
    }

    @Test
    @DisplayName("V019: EMP_ROLE 그룹에 ADMIN 코드가 등록되어 있다(관리자 권한은 HR ROLE_CD='ADMIN'으로 부여)")
    void emp_role_has_admin_code() {
        String adminName = jdbc.queryForObject(
                "SELECT CD_NM FROM CD_COMMON WHERE GRP_CD='EMP_ROLE' AND CD_VAL='ADMIN'", String.class);
        assertThat(adminName).isEqualTo("관리자");
    }
}
