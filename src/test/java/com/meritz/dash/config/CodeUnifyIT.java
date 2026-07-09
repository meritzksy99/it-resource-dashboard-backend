package com.meritz.dash.config;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class CodeUnifyIT extends AbstractOracleIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V003: EMP_STATUS 코드화 + SR_CLS 매핑 + 재직코드 재코딩")
    void code_unify_applied() {
        // EMP_STATUS 코드값이 01/02
        Integer status = jdbc.queryForObject(
            "SELECT COUNT(*) FROM CD_COMMON WHERE GRP_CD='EMP_STATUS' AND CD_VAL IN ('01','02')", Integer.class);
        assertThat(status).isEqualTo(2);
        // SR_CLS 그룹 6건 (V003 4건 + V011 데이터변경/원장변경 2건)
        Integer srcls = jdbc.queryForObject(
            "SELECT COUNT(*) FROM CD_COMMON WHERE GRP_CD='SR_CLS'", Integer.class);
        assertThat(srcls).isEqualTo(6);
        // SR_TPCD → SR_CLS(ATTR1) 매핑 — V005 패딩 + V011 재매핑 반영
        // '01' 개발요청→01, '02' 유지보수→01(V011), '18' 데이타변경→04(V011), '19' 원장변경→05(V011)
        assertThat(jdbc.queryForObject(
            "SELECT ATTR1 FROM CD_COMMON WHERE GRP_CD='SR_TPCD' AND CD_VAL='01'", String.class)).isEqualTo("01");
        assertThat(jdbc.queryForObject(
            "SELECT ATTR1 FROM CD_COMMON WHERE GRP_CD='SR_TPCD' AND CD_VAL='02'", String.class)).isEqualTo("01");
        assertThat(jdbc.queryForObject(
            "SELECT ATTR1 FROM CD_COMMON WHERE GRP_CD='SR_TPCD' AND CD_VAL='18'", String.class)).isEqualTo("04");
        assertThat(jdbc.queryForObject(
            "SELECT ATTR1 FROM CD_COMMON WHERE GRP_CD='SR_TPCD' AND CD_VAL='19'", String.class)).isEqualTo("05");
        // HR 시드 STATUS_CD가 '01'로 재코딩됨(재직) — V002 4명(재코딩) + V013 14명('01' 직접) = 18명
        Integer active = jdbc.queryForObject(
            "SELECT COUNT(*) FROM HR_DEVELOPER WHERE STATUS_CD='01'", Integer.class);
        assertThat(active).isEqualTo(18);
    }
}
