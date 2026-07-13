package com.meritz.dash.weekly;

import com.meritz.dash.mapper.legacy.WeeklySrMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WeeklySrMapper.selectSrRef 기간계 통합 테스트(legacy-fixture, DevSrMapperIT 패턴 재사용).
 * <p>
 * <b>주의(Red 단계)</b>: {@code mapper/legacy/WeeklySrMapper.xml} 은 아직 namespace 만 있다.
 * 이 테스트는 <b>컴파일 확인</b>까지가 목표.
 */
class WeeklySrMapperIT extends LegacyFixture {

    @Autowired WeeklySrMapper mapper;

    @Autowired
    @Qualifier("legacyDataSource")
    DataSource ds;

    @BeforeEach
    void seedWeeklySr() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            // 이 테스트 전용 SR 만 멱등 시드(다른 IT 의 공유 seed 를 건드리지 않음).
            s.executeUpdate("DELETE FROM TBCPPE091M00 WHERE SR_NO = 'SR26000201'");
            s.executeUpdate("""
                INSERT INTO TBCPPE091M00
                  (SR_NO, SRNO, SR_TPCD, SR_DETL_TPCD, SR_REG_STAT_CODE, DPCD, PRCH_DPCD,
                   SPIC_EMPNO, PRCH_EMPNO, TITL_CNTT, REG_DATE, RFLC_SCDL_DATE, MNPL_EMPNO)
                VALUES ('SR26000201', 201, '01', '0101', '04', 'D101', 'D101',
                        'E0002', 'E0009', '주간보고 대상 SR', '20260701', '20260720', 'E0002')""");
            c.commit();
        }
    }

    @AfterEach
    void cleanupWeeklySr() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.executeUpdate("DELETE FROM TBCPPE091M00 WHERE SR_NO = 'SR26000201'");
            c.commit();
        }
    }

    @Test
    @DisplayName("selectSrRef: SR_NO 로 단건 조회하면 TITL_CNTT/RFLC_SCDL_DATE 가 매핑된다")
    void select_sr_ref_maps_titl_and_plan_date() {
        SrRef ref = mapper.selectSrRef("SR26000201");

        assertThat(ref).isNotNull();
        assertThat(ref.srNo()).isEqualTo("SR26000201");
        assertThat(ref.srTitl()).isEqualTo("주간보고 대상 SR");
        assertThat(ref.srPlanDate()).isEqualTo("20260720");
    }

    @Test
    @DisplayName("selectSrRef: 존재하지 않는 SR → null")
    void select_sr_ref_missing_returns_null() {
        SrRef ref = mapper.selectSrRef("SRNONE999");

        assertThat(ref).isNull();
    }
}
