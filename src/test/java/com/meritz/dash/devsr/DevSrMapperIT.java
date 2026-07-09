package com.meritz.dash.devsr;

import com.meritz.dash.mapper.legacy.DevSrMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DevSrMapper.selectDevSrs 기간계 통합 테스트(legacy-fixture).
 * 계획 수립 SR = 담당자(093.SPIC_EMPNO, 승인이력) 기준 · SR등록('02') = 신청자(091.PRCH_EMPNO) 기준.
 * 종료('08')·SR_TPCD '15'·상태 NOT IN 목록 제외.
 * <p>
 * 싱글톤(재사용) 컨테이너 대비: PRCH_EMPNO·MSG_CNTT 컬럼을 멱등 ALTER 로 보강하고,
 * 이 테스트 전용 SR(SR260000 91~94)만 매번 DELETE 후 INSERT 한다(공유 seed.sql 은 건드리지 않음).
 * 공유 seed 의 다른 SR 이 섞일 수 있으므로 contains/doesNotContain 로 검증한다.
 */
class DevSrMapperIT extends LegacyFixture {

    @Autowired DevSrMapper mapper;

    @Autowired
    @Qualifier("legacyDataSource")
    DataSource ds;

    @BeforeEach
    void setupDevSrRows() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            addColumnIfMissing(s, "PRCH_EMPNO VARCHAR2(9)");
            addColumnIfMissing(s, "MSG_CNTT VARCHAR2(4000)");
            s.executeUpdate("DELETE FROM TBCPPE093L00 WHERE SR_NO IN ('SR26000091','SR26000092','SR26000093','SR26000094')");
            s.executeUpdate("DELETE FROM TBCPPE091M00 WHERE SR_NO IN ('SR26000091','SR26000092','SR26000093','SR26000094')");

            // SR91: 진행('04'), 담당 E0002 승인이력 → planYn='Y'
            s.executeUpdate("""
                INSERT INTO TBCPPE091M00
                  (SR_NO, SRNO, SR_TPCD, SR_DETL_TPCD, SR_REG_STAT_CODE, DPCD, PRCH_DPCD,
                   SPIC_EMPNO, PRCH_EMPNO, TITL_CNTT, MSG_CNTT, JOB_MANM, REG_DATE, RFLC_SCDL_DATE, MNPL_EMPNO)
                VALUES ('SR26000091', 91, '01', '0101', '04', 'D101', 'D101',
                        'E0002', 'E0009', '진행 SR', 'content-A', NULL, '20260501', '20260520', 'E0002')""");
            s.executeUpdate("""
                INSERT INTO TBCPPE093L00
                  (SR_NO, SRNO, SPIC_EMPNO, JOB_EXEC_HOUR, JOB_MANM, FIN_DATE, APRV_YN, MNPL_EMPNO)
                VALUES ('SR26000091', 91, 'E0002', '166', '1.0', '20260528', 'Y', 'E0002')""");

            // SR92: 등록('02', 계획 미수립), 신청자 E0002 → planYn='N', 시간/MM/반영예정일 없음
            s.executeUpdate("""
                INSERT INTO TBCPPE091M00
                  (SR_NO, SRNO, SR_TPCD, SR_DETL_TPCD, SR_REG_STAT_CODE, DPCD, PRCH_DPCD,
                   SPIC_EMPNO, PRCH_EMPNO, TITL_CNTT, MSG_CNTT, JOB_MANM, REG_DATE, RFLC_SCDL_DATE, MNPL_EMPNO)
                VALUES ('SR26000092', 92, '02', '0201', '02', 'D101', 'D101',
                        NULL, 'E0002', '등록 SR', 'content-B', NULL, '20260502', NULL, NULL)""");

            // SR93: 종료('08'), 담당 E0002 승인이력 있으나 제외되어야 함
            s.executeUpdate("""
                INSERT INTO TBCPPE091M00
                  (SR_NO, SRNO, SR_TPCD, SR_DETL_TPCD, SR_REG_STAT_CODE, DPCD, PRCH_DPCD,
                   SPIC_EMPNO, PRCH_EMPNO, TITL_CNTT, MSG_CNTT, JOB_MANM, REG_DATE, RFLC_SCDL_DATE, MNPL_EMPNO)
                VALUES ('SR26000093', 93, '01', '0101', '08', 'D101', 'D101',
                        'E0002', 'E0002', '종료 SR', 'content-C', '1.0', '20260410', '20260430', 'E0002')""");
            s.executeUpdate("""
                INSERT INTO TBCPPE093L00
                  (SR_NO, SRNO, SPIC_EMPNO, JOB_EXEC_HOUR, JOB_MANM, FIN_DATE, APRV_YN, MNPL_EMPNO)
                VALUES ('SR26000093', 93, 'E0002', '166', '1.0', '20260429', 'Y', 'E0002')""");

            // SR94: 진행('04'), 담당은 E0002 지만 승인이력은 참여자 E0003 → E0003 이 담당자 기준으로 조회
            s.executeUpdate("""
                INSERT INTO TBCPPE091M00
                  (SR_NO, SRNO, SR_TPCD, SR_DETL_TPCD, SR_REG_STAT_CODE, DPCD, PRCH_DPCD,
                   SPIC_EMPNO, PRCH_EMPNO, TITL_CNTT, MSG_CNTT, JOB_MANM, REG_DATE, RFLC_SCDL_DATE, MNPL_EMPNO)
                VALUES ('SR26000094', 94, '01', '0101', '04', 'D101', 'D101',
                        'E0002', 'E0009', '참여 SR', 'content-D', NULL, '20260501', '20260525', 'E0002')""");
            s.executeUpdate("""
                INSERT INTO TBCPPE093L00
                  (SR_NO, SRNO, SPIC_EMPNO, JOB_EXEC_HOUR, JOB_MANM, FIN_DATE, APRV_YN, MNPL_EMPNO)
                VALUES ('SR26000094', 94, 'E0003', '208', '1.3', '20260528', 'Y', 'E0003')""");
            c.commit();
        }
    }

    @AfterEach
    void cleanupDevSrRows() throws Exception {
        // 싱글톤 컨테이너 오염 방지 — 이 테스트가 넣은 SR 만 제거해 base seed 상태로 복원
        // (LegacySrMapperIT 등 E0002/E0003 합계를 정확히 검증하는 테스트를 깨뜨리지 않도록).
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.executeUpdate("DELETE FROM TBCPPE093L00 WHERE SR_NO IN ('SR26000091','SR26000092','SR26000093','SR26000094')");
            s.executeUpdate("DELETE FROM TBCPPE091M00 WHERE SR_NO IN ('SR26000091','SR26000092','SR26000093','SR26000094')");
            c.commit();
        }
    }

    private void addColumnIfMissing(Statement s, String colDef) {
        try {
            s.executeUpdate("ALTER TABLE TBCPPE091M00 ADD (" + colDef + ")");
        } catch (SQLException alreadyExists) {
            // ORA-01430: column being added already exists — 재사용 컨테이너에서는 정상
        }
    }

    @Test
    @DisplayName("담당자/신청자 E0002: 진행 SR91(계획수립)+등록 SR92(신청자), 종료 SR93 제외")
    void devSrs_forOwnerAndApplicant() {
        List<DevSrRow> rows = mapper.selectDevSrs(List.of("E0002"));

        assertThat(rows).extracting(DevSrRow::srNo).contains("SR26000091", "SR26000092");
        assertThat(rows).extracting(DevSrRow::srNo).doesNotContain("SR26000093"); // 종료 제외

        DevSrRow sr91 = only(rows, "SR26000091");
        assertThat(sr91.planYn()).isEqualTo("Y");
        assertThat(sr91.statusCode()).isEqualTo("04");
        assertThat(sr91.jobHours()).isEqualTo(166.0);
        assertThat(sr91.jobMm()).isEqualTo(1.0);
        assertThat(sr91.msgCntt()).isEqualTo("content-A");

        DevSrRow sr92 = only(rows, "SR26000092");
        assertThat(sr92.planYn()).isEqualTo("N");        // 등록 = 계획 미수립
        assertThat(sr92.statusCode()).isEqualTo("02");
        assertThat(sr92.jobHours()).isZero();
        assertThat(sr92.jobMm()).isZero();
        assertThat(sr92.rflcScdlDate()).isNull();        // 반영예정일 미정
        assertThat(sr92.msgCntt()).isEqualTo("content-B");
    }

    @Test
    @DisplayName("참여자 E0003: 담당자(093.SPIC_EMPNO) 기준으로 진행 SR94 조회(208h)")
    void devSrs_forContributor() {
        List<DevSrRow> rows = mapper.selectDevSrs(List.of("E0003"));

        assertThat(rows).extracting(DevSrRow::srNo).contains("SR26000094");
        DevSrRow sr94 = only(rows, "SR26000094");
        assertThat(sr94.planYn()).isEqualTo("Y");
        assertThat(sr94.jobHours()).isEqualTo(208.0);
        assertThat(sr94.msgCntt()).isEqualTo("content-D");
    }

    @Test
    @DisplayName("상태코드 오름차순 정렬")
    void devSrs_orderedByStatus() {
        List<DevSrRow> rows = mapper.selectDevSrs(List.of("E0002"));
        assertThat(rows).extracting(DevSrRow::statusCode).isSorted();
    }

    private static DevSrRow only(List<DevSrRow> rows, String srNo) {
        return rows.stream().filter(r -> r.srNo().equals(srNo)).findFirst().orElseThrow();
    }
}
